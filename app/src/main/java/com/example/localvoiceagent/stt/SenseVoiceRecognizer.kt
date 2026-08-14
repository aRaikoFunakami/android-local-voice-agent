package com.example.localvoiceagent.stt

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * sherpa-onnx SenseVoice（ja 対応）+ Silero VAD の SpeechRecognizer 実装。
 *
 * 48kHz int16 → FIR ローパス + 1/3 間引きで 16kHz float → Silero VAD →
 * speech セグメントを SenseVoice で認識（セグメント単位の final のみ。
 * ストリーミング partial は Zipformer 系へ差し替えで対応可能）。
 *
 * モデルは scripts/fetch_stt_models.sh が /data/local/tmp/stt/ へ push する。
 */
class SenseVoiceRecognizer : SpeechRecognizer {
    companion object {
        private const val DIR = "/data/local/tmp/stt"
        private const val ASR =
            "$DIR/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"

        fun modelAvailable(): Boolean =
            File("$ASR/model.int8.onnx").canRead() &&
                File("$DIR/silero_vad_v5.onnx").canRead()

        // 48k→16k 間引き用 33-tap windowed-sinc ローパス（cutoff 7kHz、DC gain 1）。
        // ponytail: Kotlin 実装。品質が問題になれば engine 側 PushResampler へ移行。
        private val FIR = FloatArray(33).also { h ->
            val fc = 7000.0 / 48000.0
            val m = h.size - 1
            var sum = 0.0
            for (i in h.indices) {
                val x = i - m / 2.0
                val sinc = if (x == 0.0) 2 * fc
                           else Math.sin(2 * Math.PI * fc * x) / (Math.PI * x)
                val w = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / m)
                h[i] = (sinc * w).toFloat()
                sum += h[i]
            }
            for (i in h.indices) h[i] = (h[i] / sum).toFloat()
        }
    }

    override var onFinalResult: ((String) -> Unit)? = null

    private val queue = ArrayBlockingQueue<ShortArray>(64)
    private val running = AtomicBoolean(true)
    private val speechActive = AtomicBoolean(false)

    private val vad: Vad by lazy {
        Vad(
            null,
            VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "$DIR/silero_vad_v5.onnx",
                    threshold = 0.5f,
                    minSilenceDuration = 0.4f,
                    minSpeechDuration = 0.2f,
                    maxSpeechDuration = 15.0f,
                ),
                sampleRate = 16000,
            )
        )
    }

    private val recognizer: OfflineRecognizer by lazy {
        OfflineRecognizer(
            null,
            OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = "$ASR/model.int8.onnx",
                        language = "ja",
                        useInverseTextNormalization = true,
                    ),
                    tokens = "$ASR/tokens.txt",
                    numThreads = 2,
                ),
            )
        )
    }

    // FIR 遅延線（間引き前 48kHz 側）
    private val firState = FloatArray(FIR.size)
    private var firPos = 0

    private val worker = Thread({
        val pending = ArrayList<Float>(16000)
        while (running.get()) {
            val frame = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            // 48k int16 → 16k float [-1,1]（FIR → 1/3 間引き）
            pending.clear()
            for (s in frame) {
                firState[firPos] = s / 32768.0f
                firPos = (firPos + 1) % FIR.size
                // 3 サンプルごとに 1 出力
                if (decimCount++ % 3 == 0) {
                    var acc = 0.0f
                    var idx = firPos
                    for (c in FIR) {
                        idx = if (idx == 0) FIR.size - 1 else idx - 1
                        acc += c * firState[idx]
                    }
                    pending.add(acc)
                }
            }
            vad.acceptWaveform(pending.toFloatArray())
            speechActive.set(vad.isSpeechDetected())
            while (!vad.empty()) {
                val segment = vad.front()
                vad.pop()
                val stream = recognizer.createStream()
                stream.acceptWaveform(segment.samples, 16000)
                recognizer.decode(stream)
                val text = recognizer.getResult(stream).text.trim()
                stream.release()
                if (text.isNotEmpty()) onFinalResult?.invoke(text)
            }
        }
    }, "stt-worker")

    private var decimCount = 0

    init {
        worker.start()
    }

    override fun acceptAudio(samples: ShortArray, sampleRate: Int) {
        require(sampleRate == 48000) { "expects 48kHz input" }
        queue.offer(samples)  // 満杯時は drop（認識遅延を溜めない）
    }

    override fun isSpeechActive(): Boolean = speechActive.get()

    override fun reset() {
        queue.clear()
        vad.clear()
    }

    override fun close() {
        running.set(false)
        worker.join(2000)
        vad.release()
        recognizer.release()
    }
}
