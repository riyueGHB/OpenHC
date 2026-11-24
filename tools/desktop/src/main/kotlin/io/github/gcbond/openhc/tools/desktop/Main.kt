package io.github.gcbond.openhc.tools.desktop

import io.github.gcbond.openhc.core.auth.AuthMode
import io.github.gcbond.openhc.core.common.identity.IdentityAssets
import io.github.gcbond.openhc.core.session.DisplaySettings
import io.github.gcbond.openhc.core.session.EndReason
import io.github.gcbond.openhc.core.session.ReceiverConfig
import io.github.gcbond.openhc.core.session.ReceiverSession
import io.github.gcbond.openhc.core.session.SessionEvent
import io.github.gcbond.openhc.core.session.SocketRunner
import java.io.IOException
import java.io.PrintStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val options = try {
        DesktopOptions.parse(args)
    } catch (e: HelpRequested) {
        println(DesktopOptions.USAGE)
        exitProcess(0)
    } catch (e: UsageException) {
        System.err.println("参数错误：${e.message}")
        System.err.println()
        System.err.println(DesktopOptions.USAGE)
        exitProcess(1)
    }
    exitProcess(DesktopApp(options, System.out).run())
}

/** 启动阶段的错误：参数、文件、配置不对，还没开始连手机。 */
private class StartupFailure(message: String) : Exception(message)

/**
 * 一次完整的有线会话：读身份资产 → 生成 carInfo 并写文件 → 先开好控制监听 → 提示可以 USB 拉起 →
 * 手机连上后跑完整会话 → 结束时打印统计。USB 拉起（发 carInfo、配网卡 IP）由外部脚本完成。
 *
 * 日志只写阶段、时间和数量，不打印密钥、会话密钥和完整的设备标识；连接码只在首次配对时醒目地显示一次。
 */
internal class DesktopApp(private val options: DesktopOptions, private val out: PrintStream) {
    private val startNanos = System.nanoTime()

    @Volatile private var phoneAt = 0L
    @Volatile private var playAt = 0L
    @Volatile private var firstFrameAt = 0L
    @Volatile private var endReason: EndReason? = null

    private class Prepared(val config: ReceiverConfig, val video: VideoFileSink)

    /** 返回进程退出码：收到了视频帧为 0，启动失败为 1，会话没出画面为 2。 */
    fun run(): Int {
        log("OpenHC 桌面调试程序")
        val prepared = try {
            prepare()
        } catch (e: StartupFailure) {
            log("启动失败：${e.message}")
            return 1
        }
        val video = prepared.video
        val session = ReceiverSession(prepared.config, video)
        val runner = SocketRunner(session, ::onEvent, SocketRunner.Options(log = { log("[执行器] $it") }))

        // Ctrl-C：先让会话按协议收尾（TEARDOWN 等），再等主线程打完统计。
        val finished = CountDownLatch(1)
        val exitHook = thread(start = false, name = "openhc-desktop-exit") {
            if (finished.count > 0L) {
                log("收到退出信号，正在结束会话")
                runner.close()
                finished.await(5, TimeUnit.SECONDS)
            }
        }
        Runtime.getRuntime().addShutdownHook(exitHook)

        runner.start()
        options.durationSeconds?.let { seconds ->
            thread(isDaemon = true, name = "openhc-desktop-duration") {
                if (!runner.awaitTermination(TimeUnit.SECONDS.toMillis(seconds))) {
                    log("已运行 $seconds 秒（--duration），结束会话")
                    runner.stop()
                }
            }
        }
        while (!runner.awaitTermination(60_000)) {
            // 一直等到会话结束（手机断开、出错、--duration 到期或 Ctrl-C）。
        }
        runner.failure?.let { log("执行器意外结束：${it.javaClass.simpleName}：${it.message}") }
        try {
            video.close()
        } catch (e: IOException) {
            log("关闭视频文件失败：${e.message}")
        }
        printStats(video)
        finished.countDown()
        return if (video.frames > 0) 0 else 2
    }

    private fun prepare(): Prepared {
        val identity = try {
            IdentityAssets.parse(String(Files.readAllBytes(options.identity), Charsets.UTF_8))
        } catch (e: IOException) {
            throw StartupFailure("读不了身份资产文件 ${options.identity}：${e.message}")
        } catch (e: IllegalArgumentException) {
            throw StartupFailure("身份资产不合法：${e.message}")
        }
        log("身份资产已读取：${options.identity}")

        val trustStore = try {
            FileTrustStore.open(options.trustDir)
        } catch (e: IOException) {
            throw StartupFailure("信任库不可用：${e.message}")
        }
        log("信任库：${trustStore.file}（已配对的手机 ${trustStore.peerCount} 台）")

        val config = try {
            ReceiverConfig(
                identity = identity,
                bluetoothAddress = options.bluetooth,
                localIp = options.ip,
                controlPort = options.port,
                display = DisplaySettings(
                    width = options.width,
                    height = options.height,
                    fps = options.fps,
                    dpi = options.dpi,
                    density = options.density,
                    gop = options.gop,
                    bitrate = options.bitrate,
                ),
                authEnvelopeVersion = identity.authEnvelopeVersion,
                trustStore = trustStore,
                controlBindHost = options.bindIp,
                controlLocalFilter = options.localFilter,
            )
        } catch (e: IllegalArgumentException) {
            throw StartupFailure("配置不合法：${e.message}")
        }
        log("本机 deviceId：${config.deviceId.take(4)}…（只显示前 4 位）；画布 ${options.width}x${options.height} @ ${options.fps} fps")

        val carInfo = try {
            config.carInfo()
        } catch (e: IllegalArgumentException) {
            throw StartupFailure("生成 carInfo 失败：${e.message}")
        }
        val carInfoOut = options.carInfoOut
        if (carInfoOut != null) {
            try {
                PrivateFiles.writeAtomically(carInfoOut, carInfo)
            } catch (e: IOException) {
                throw StartupFailure("写 carInfo 文件 $carInfoOut 失败：${e.message}")
            }
            log("carInfo（${carInfo.size} 字节）已写到 $carInfoOut：车机 ${options.ip}，控制端口 ${options.port}")
        } else {
            log("carInfo（${carInfo.size} 字节）已生成，没有指定 --carinfo-out，不落盘")
        }

        val video = try {
            VideoFileSink.create(options.videoOut)
        } catch (e: FileAlreadyExistsException) {
            throw StartupFailure("视频输出文件已存在，不覆盖：${options.videoOut}")
        } catch (e: IOException) {
            throw StartupFailure("建不了视频输出文件 ${options.videoOut}：${e.message}")
        }
        log("视频输出：${options.videoOut}")
        return Prepared(config, video)
    }

    /** 会话事件，在协议线程上回调。 */
    private fun onEvent(event: SessionEvent) {
        when (event) {
            is SessionEvent.ControlListening -> {
                val filter = if (options.bindIp == ReceiverConfig.WILDCARD && options.localFilter) "，只接受连到 ${options.ip} 的连接" else ""
                log("控制监听已就绪：${options.bindIp}:${event.port}$filter")
                banner("可以开始 USB 拉起（发 carInfo，给手机网卡配 ${options.ip}/24）")
            }
            is SessionEvent.PhoneConnected -> {
                phoneAt = System.nanoTime()
                log("手机已连上控制通道（手机地址 ${event.phoneIp}）")
            }
            is SessionEvent.ControlHandshakeCompleted ->
                log("控制通道握手完成（帧加密：${if (event.encrypted) "开" else "关"}）")
            is SessionEvent.AuthStarted -> log(
                if (event.mode == AuthMode.PIN_PAIRING) "开始认证：首次配对，要在手机上输入连接码" else "开始认证：免码回连",
            )
            is SessionEvent.ShowPinCode -> banner("首次配对，请在手机上输入连接码：  ${event.pinCode.toCharArray().joinToString(" ")}")
            is SessionEvent.Authenticated -> log(
                if (event.mode == AuthMode.PIN_PAIRING) "认证成功：首次配对完成，配对记录已写入信任库，下次免码回连" else "认证成功：免码回连",
            )
            is SessionEvent.DeviceSessionReady -> log("设备会话已加密，保活方式：${event.keepAlive}")
            is SessionEvent.ProjectionRequested -> log("手机通告了投屏端口 ${event.port}，开始连接投屏")
            SessionEvent.ProjectionPaused -> log("手机暂停了投屏（投屏界面上点了「车机」图标）")
            is SessionEvent.VideoNegotiated ->
                log("投屏参数：${event.video.width}x${event.video.height} @ ${event.video.fps} fps")
            SessionEvent.PlayAccepted -> {
                playAt = System.nanoTime()
                log("手机已接受 PLAY，等待视频")
            }
            is SessionEvent.VideoStarted -> {
                firstFrameAt = System.nanoTime()
                log("收到首帧" + (if (phoneAt != 0L) "（距手机连上 ${seconds(firstFrameAt - phoneAt)}）" else ""))
            }
            is SessionEvent.Diagnostic -> log("诊断（${event.layer}）：${event.message}")
            is SessionEvent.Ended -> {
                endReason = event.reason
                log("会话结束：${event.reason}")
            }
        }
    }

    private fun printStats(video: VideoFileSink) {
        out.println()
        out.println("======== 统计 ========")
        out.println("结束原因：${endReason ?: "未知"}")
        out.println("视频帧：${video.frames}（关键帧 ${video.keyFrames}）")
        out.println("视频字节：${video.bytes}（${String.format(Locale.ROOT, "%.2f", video.bytes / 1048576.0)} MiB）")
        if (firstFrameAt != 0L && phoneAt != 0L) {
            val afterPlay = if (playAt != 0L) "，PLAY 被接受后 ${seconds(firstFrameAt - playAt)}" else ""
            out.println("首帧耗时：手机连上控制通道后 ${seconds(firstFrameAt - phoneAt)}$afterPlay")
        } else {
            out.println("首帧耗时：没有收到视频帧")
        }
        out.println("H.264 文件：${options.videoOut}")
        out.println("======================")
    }

    private fun banner(text: String) {
        val line = "=".repeat(64)
        out.println(line)
        out.println("  $text")
        out.println(line)
    }

    private fun log(message: String) {
        val clock = LocalTime.now().format(CLOCK)
        out.println("[$clock +${seconds(System.nanoTime() - startNanos)}] $message")
    }

    private companion object {
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

        fun seconds(nanos: Long): String = String.format(Locale.ROOT, "%.3f 秒", nanos / 1e9)
    }
}
