package com.example.localvoiceagent

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
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
        // エンジンのバージョン表示は Issue #7（.so ロードスモーク）で接続する
        findViewById<TextView>(R.id.engineVersion).text =
            "engine: not loaded\n" + SherpaRuntime.status()
        requestMic()

        initLlm()
        llmSend.setOnClickListener {
            val prompt = llmInput.text.toString().trim()
            if (prompt.isEmpty()) return@setOnClickListener
            sendToLlm(prompt)
        }
        // 自動テスト用: adb shell am start ... --es prompt "..." で日本語プロンプトを注入
        intent.getStringExtra("prompt")?.let { pendingPrompt = it }
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
