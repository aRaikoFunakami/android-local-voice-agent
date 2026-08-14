package com.example.localvoiceagent.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.example.localvoiceagent.LocalAudioEngine
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Render パイプライン（開発計画 §6）:
 *   frame source → APM ProcessReverseStream（AEC 参照）→ AudioTrack → Speaker
 *
 * 再生する PCM は必ず先に processRender へ通す（AudioTrack 直接書き込み禁止）。
 * AEC への参照信号と実再生信号は同一 buffer（同一経路）。
 *
 * frame source は 10ms frame を fill する関数。true = frame あり、false = 無音を再生。
 * （TTS 接続（#20）はこの source に TTS キューを差すだけ）
 */
class RenderPipeline(
    private val engineHandle: () -> Long,
    private val fillFrame: (ByteBuffer) -> Boolean,
) {
    val framesRendered = AtomicLong()
    val processErrors = AtomicLong()
    val writeErrors = AtomicLong()

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var writerThread: Thread? = null
    private var track: AudioTrack? = null

    private val dumpQueue = ArrayBlockingQueue<ByteArray>(256)
    val dumpDropped = AtomicLong()
    @Volatile private var dumpDir: File? = null

    fun enableDump(dir: File) {
        dumpDir = dir
    }

    /** AudioTrack 由来の underrun 回数。 */
    fun underrunCount(): Int = track?.underrunCount ?: 0

    fun start(): Boolean {
        if (running.getAndSet(true)) return true
        val minBuf = AudioTrack.getMinBufferSize(
            LocalAudioEngine.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(LocalAudioEngine.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf * 2, LocalAudioEngine.FRAME_BYTES * 8))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            running.set(false)
            return false
        }
        track = t
        startDumpWriter()

        thread = Thread({
            val frame = LocalAudioEngine.newFrameBuffer()
            t.play()
            while (running.get()) {
                frame.clear()
                if (!fillFrame(frame)) {
                    // 無音 frame（TTS 停止中も reverse stream への投入は継続し、
                    // AEC の render 経路を実再生と常に一致させる）
                    for (i in 0 until LocalAudioEngine.FRAME_SAMPLES) {
                        frame.putShort(i * 2, 0)
                    }
                }
                val h = engineHandle()
                if (h != 0L) {
                    if (LocalAudioEngine.processRender(h, frame) != 0) {
                        processErrors.incrementAndGet()
                    }
                }
                frame.position(0)
                val n = t.write(frame, LocalAudioEngine.FRAME_BYTES,
                                AudioTrack.WRITE_BLOCKING)
                if (n != LocalAudioEngine.FRAME_BYTES) {
                    writeErrors.incrementAndGet()
                } else {
                    framesRendered.incrementAndGet()
                }
                if (dumpDir != null) {
                    val copy = ByteArray(LocalAudioEngine.FRAME_BYTES)
                    frame.position(0)
                    frame.get(copy)
                    if (!dumpQueue.offer(copy)) dumpDropped.incrementAndGet()
                }
            }
            t.stop()
            t.release()
            track = null
        }, "render").apply { start() }
        return true
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        thread?.join(2000)
        thread = null
        writerThread?.join(3000)
        writerThread = null
    }

    private fun startDumpWriter() {
        writerThread = Thread({
            var out: BufferedOutputStream? = null
            try {
                while (running.get() || dumpQueue.isNotEmpty()) {
                    val e = dumpQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    val dir = dumpDir ?: continue
                    if (out == null) {
                        out = BufferedOutputStream(
                            FileOutputStream(File(dir, "render_reference.pcm")))
                    }
                    out.write(e)
                }
            } finally {
                out?.close()
            }
        }, "render-dump").apply { start() }
    }
}
