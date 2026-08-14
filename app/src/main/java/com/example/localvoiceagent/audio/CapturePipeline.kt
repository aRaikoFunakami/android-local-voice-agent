package com.example.localvoiceagent.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.localvoiceagent.LocalAudioEngine
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Capture パイプライン（開発計画 §5）:
 *   AudioRecord(48kHz mono, VOICE_RECOGNITION) → 10ms frame → APM ProcessStream
 *   → clean PCM を consumer へ / debug PCM dump（明示 opt-in 時のみ）
 *
 * - capture thread は専用 Thread（UI thread から processCapture を呼ばない）
 * - READ_BLOCKING で 480 samples ちょうどを読むため framing 用の中間 buffer 不要
 *   （AudioRecord の read サイズ仮定はしない、という原案の意図は
 *    「要求サイズが満たされるまでブロックする API 契約」で満たす）
 * - capture thread では file I/O・alloc をしない。dump は queue 経由で writer thread が書く
 *   （dump 有効時のみ frame ごとに ByteArray を確保する。debug 専用の割り切り）
 */
class CapturePipeline(
    private val onCleanFrame: ((ByteBuffer) -> Unit)? = null,
    // APM 投入前の capture frame への介入（合成 echo 注入などデバッグ用途、Issue #16）
    private val preProcess: ((ByteBuffer) -> Unit)? = null,
) {
    val framesProcessed = AtomicLong()
    val readErrors = AtomicLong()
    val processErrors = AtomicLong()
    val dumpDropped = AtomicLong()

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var writerThread: Thread? = null
    private var engineHandle = 0L

    // debug dump（raw/aec の 2 系統を 1 エントリに束ねる）
    private class DumpEntry(val raw: ByteArray, val aec: ByteArray)
    private val dumpQueue = ArrayBlockingQueue<DumpEntry>(256)
    @Volatile private var dumpDir: File? = null

    /** dump 先ディレクトリを設定すると raw_capture.pcm / aec_output.pcm を書く。 */
    fun enableDump(dir: File) {
        dumpDir = dir
    }

    @SuppressLint("MissingPermission")  // RECORD_AUDIO は呼び出し側が取得済み
    fun start(aec: Boolean = true, ns: Boolean = true, agc: Boolean = true): Boolean {
        if (running.getAndSet(true)) return true
        engineHandle = LocalAudioEngine.create(aec, ns, agc)
        if (engineHandle == 0L) {
            running.set(false)
            return false
        }

        val minBuf = AudioRecord.getMinBufferSize(
            LocalAudioEngine.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            LocalAudioEngine.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 2, LocalAudioEngine.FRAME_BYTES * 8)
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            LocalAudioEngine.destroy(engineHandle)
            engineHandle = 0L
            running.set(false)
            return false
        }

        startDumpWriter()
        thread = Thread({
            val inBuf = LocalAudioEngine.newFrameBuffer()
            val outBuf = LocalAudioEngine.newFrameBuffer()
            record.startRecording()
            while (running.get()) {
                inBuf.clear()
                val n = record.read(inBuf, LocalAudioEngine.FRAME_BYTES,
                                    AudioRecord.READ_BLOCKING)
                if (n != LocalAudioEngine.FRAME_BYTES) {
                    readErrors.incrementAndGet()
                    continue
                }
                preProcess?.invoke(inBuf)
                val r = LocalAudioEngine.processCapture(engineHandle, inBuf, outBuf)
                if (r != 0) {
                    processErrors.incrementAndGet()
                    continue
                }
                framesProcessed.incrementAndGet()
                onCleanFrame?.invoke(outBuf)

                if (dumpDir != null) {
                    val raw = ByteArray(LocalAudioEngine.FRAME_BYTES)
                    val aec = ByteArray(LocalAudioEngine.FRAME_BYTES)
                    inBuf.position(0); inBuf.get(raw)
                    outBuf.position(0); outBuf.get(aec)
                    if (!dumpQueue.offer(DumpEntry(raw, aec))) dumpDropped.incrementAndGet()
                }
            }
            record.stop()
            record.release()
        }, "capture").apply { start() }
        return true
    }

    /** render 側（Issue #14/#15）が同じ engine を共有するためのハンドル。 */
    fun engineHandle(): Long = engineHandle

    fun setStreamDelayMs(ms: Int) {
        if (engineHandle != 0L) LocalAudioEngine.setStreamDelayMs(engineHandle, ms)
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        thread?.join(2000)
        thread = null
        writerThread?.join(3000)
        writerThread = null
        if (engineHandle != 0L) {
            LocalAudioEngine.destroy(engineHandle)
            engineHandle = 0L
        }
    }

    private fun startDumpWriter() {
        writerThread = Thread({
            var rawOut: BufferedOutputStream? = null
            var aecOut: BufferedOutputStream? = null
            try {
                while (running.get() || dumpQueue.isNotEmpty()) {
                    val e = dumpQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                        ?: continue
                    val dir = dumpDir ?: continue
                    if (rawOut == null) {
                        rawOut = BufferedOutputStream(FileOutputStream(File(dir, "raw_capture.pcm")))
                        aecOut = BufferedOutputStream(FileOutputStream(File(dir, "aec_output.pcm")))
                    }
                    rawOut.write(e.raw)
                    aecOut?.write(e.aec)
                }
            } finally {
                rawOut?.close()
                aecOut?.close()
            }
        }, "capture-dump").apply { start() }
    }
}
