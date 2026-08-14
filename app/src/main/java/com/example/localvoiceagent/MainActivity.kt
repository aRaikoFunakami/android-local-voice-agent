package com.example.localvoiceagent

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.example.localvoiceagent.audio.CapturePipeline
import com.example.localvoiceagent.audio.RenderPipeline
import com.example.localvoiceagent.tts.AudioSink
import com.example.localvoiceagent.tts.SupertonicTts
import com.example.localvoiceagent.tts.WavWriter
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var permissionStatus: TextView
    private lateinit var llmStatus: TextView
    private lateinit var llmResponse: TextView
    private lateinit var llmSend: Button

    private val llm = LlmEngine()
    // LLM 推論専用 worker（audio thread では推論しない、開発計画 §12）
    private val inferenceWorker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        permissionStatus = findViewById(R.id.permissionStatus)
        llmStatus = findViewById(R.id.llmStatus)
        llmResponse = findViewById(R.id.llmResponse)
        llmSend = findViewById(R.id.llmSend)
        val llmInput = findViewById<EditText>(R.id.llmInput)

        permissionStatus.setOnClickListener { requestMic() }
        findViewById<TextView>(R.id.engineVersion).text =
            LocalAudioEngine.status() + "\n" + SherpaRuntime.status() + "\n" +
            LocalAudioEngine.smokeTest()
        requestMic()

        initLlm()
        llmSend.setOnClickListener {
            val prompt = llmInput.text.toString().trim()
            if (prompt.isEmpty()) return@setOnClickListener
            sendToLlm(prompt)
        }
        // 自動テスト用: adb shell am start ... --es prompt "..." で日本語プロンプトを注入
        intent.getStringExtra("prompt")?.let { pendingPrompt = it }

        // TTS デバッグ (Issue #19): 入力文を WAV へ合成。--es tts "..." でも起動可
        findViewById<Button>(R.id.ttsSynth).setOnClickListener {
            val text = llmInput.text.toString().trim().ifEmpty { return@setOnClickListener }
            synthesizeToWav(text)
        }
        intent.getStringExtra("tts")?.let { synthesizeToWav(it) }

        // Capture パイプライン (Issue #13)。--ez capture true --ez dump true で自動起動
        val captureToggle = findViewById<Button>(R.id.captureToggle)
        captureToggle.setOnClickListener { toggleCapture() }
        if (intent.getBooleanExtra("capture", false)) {
            if (intent.getBooleanExtra("dump", false)) {
                capture.enableDump(getExternalFilesDir(null)!!)
            }
            toggleCapture()
        }
        // Render テスト (Issue #14)。--ez render true で自動起動
        findViewById<Button>(R.id.renderToggle).setOnClickListener { toggleRender() }
        if (intent.getBooleanExtra("render", false)) toggleRender()
        statsTicker()
    }

    private val capture = CapturePipeline()
    private var capturing = false

    // Render テスト (Issue #14): 440Hz トーンを AEC 参照経由で再生
    private var renderEngineHandle = 0L  // capture 非稼働時の専用 engine
    private var rendering = false
    private var tonePhase = 0.0
    private val render = RenderPipeline(
        engineHandle = {
            if (capturing) capture.engineHandle() else renderEngineHandle
        },
        fillFrame = { buf ->
            val step = 2.0 * Math.PI * 440.0 / LocalAudioEngine.SAMPLE_RATE
            for (i in 0 until LocalAudioEngine.FRAME_SAMPLES) {
                buf.putShort(i * 2, (6000 * Math.sin(tonePhase)).toInt().toShort())
                tonePhase += step
            }
            true
        },
    )

    private fun toggleRender() {
        val btn = findViewById<Button>(R.id.renderToggle)
        if (!rendering) {
            if (!capturing && renderEngineHandle == 0L) {
                renderEngineHandle = LocalAudioEngine.create()
            }
            if (intent.getBooleanExtra("dump", false)) {
                render.enableDump(getExternalFilesDir(null)!!)
            }
            rendering = render.start()
            btn.text = if (rendering) "Render 停止" else "Render 開始(失敗)"
        } else {
            render.stop()
            rendering = false
            if (renderEngineHandle != 0L) {
                LocalAudioEngine.destroy(renderEngineHandle)
                renderEngineHandle = 0L
            }
            btn.text = "Render 開始"
        }
    }

    private fun toggleCapture() {
        val btn = findViewById<Button>(R.id.captureToggle)
        if (!capturing) {
            capturing = capture.start()
            btn.text = if (capturing) "Capture 停止" else "Capture 開始(失敗)"
        } else {
            capture.stop()
            capturing = false
            btn.text = "Capture 開始"
        }
    }

    private fun statsTicker() {
        val stats = findViewById<TextView>(R.id.captureStats)
        stats.postDelayed(object : Runnable {
            override fun run() {
                stats.text = "frames=${capture.framesProcessed.get()}" +
                    " readErr=${capture.readErrors.get()}" +
                    " procErr=${capture.processErrors.get()}" +
                    " dumpDrop=${capture.dumpDropped.get()}"
                findViewById<TextView>(R.id.renderStats).text =
                    "frames=${render.framesRendered.get()}" +
                    " underrun=${render.underrunCount()}" +
                    " procErr=${render.processErrors.get()}" +
                    " writeErr=${render.writeErrors.get()}"
                stats.postDelayed(this, 1000)
            }
        }, 1000)
    }

    private val ttsEngine by lazy { SupertonicTts() }

    private fun synthesizeToWav(text: String) {
        if (!SupertonicTts.modelAvailable()) {
            llmStatus.text = "TTS: モデル未配置（scripts/fetch_supertonic.sh を実行）"
            return
        }
        llmStatus.text = "TTS: 合成中…"
        val t0 = System.currentTimeMillis()
        inferenceWorker.execute {
            val result = runCatching {
                var info = ""
                ttsEngine.synthesize(text, object : AudioSink {
                    override fun onAudio(samples: ShortArray, sampleRate: Int, channels: Int) {
                        val out = File(getExternalFilesDir(null), "tts_debug.wav")
                        WavWriter.write(out, samples, sampleRate, channels)
                        val durMs = samples.size * 1000L / sampleRate
                        val rtf = (System.currentTimeMillis() - t0).toFloat() / durMs
                        info = "TTS: ${durMs}ms audio, ${sampleRate}Hz, RTF %.2f → ${out.name}".format(rtf)
                    }
                    override fun onEnd() {}
                })
                info
            }
            runOnUiThread {
                llmStatus.text = result.getOrElse { "TTS: 失敗 $it" }
            }
        }
    }

    private var pendingPrompt: String? = null

    private fun sendToLlm(prompt: String) {
        llmSend.isEnabled = false
        llmStatus.text = "LLM: 生成中…"
        val t0 = System.currentTimeMillis()
        inferenceWorker.execute {
            val result = runCatching { llm.ask(prompt) }
            val elapsed = System.currentTimeMillis() - t0
            runOnUiThread {
                llmSend.isEnabled = true
                llmStatus.text = "LLM: 応答 ${elapsed}ms"
                llmResponse.append("Q: $prompt\nA: ${result.getOrElse { it.toString() }}\n\n")
            }
        }
    }

    private fun initLlm() {
        if (!LlmEngine.modelAvailable()) {
            llmStatus.text = "LLM: モデル未配置（scripts/fetch_gemma.sh を実行）"
            return
        }
        llmStatus.text = "LLM: モデルロード中…"
        val t0 = System.currentTimeMillis()
        inferenceWorker.execute {
            val result = runCatching { llm.initialize() }
            val elapsed = System.currentTimeMillis() - t0
            runOnUiThread {
                if (result.isSuccess) {
                    llmStatus.text = "LLM: ready (Gemma 4 E2B, load ${elapsed}ms)"
                    llmSend.isEnabled = true
                    pendingPrompt?.let { p -> pendingPrompt = null; sendToLlm(p) }
                } else {
                    llmStatus.text = "LLM: 初期化失敗 ${result.exceptionOrNull()}"
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        inferenceWorker.execute { llm.close() }
        inferenceWorker.shutdown()
    }

    private fun requestMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            permissionStatus.setText(R.string.mic_granted)
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        permissionStatus.setText(if (granted) R.string.mic_granted else R.string.mic_denied)
    }
}
