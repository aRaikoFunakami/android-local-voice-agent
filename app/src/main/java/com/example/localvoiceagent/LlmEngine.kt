package com.example.localvoiceagent

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File

/**
 * LiteRT-LM (Gemma 4 E2B) のラッパ。
 * モデルは scripts/fetch_gemma.sh が /data/local/tmp/llm/ へ push する（初回配置方式）。
 * initialize()/ask() はブロッキング。呼び出し側が worker thread で実行すること
 * （audio realtime thread での実行は禁止、開発計画 §12）。
 */
class LlmEngine {
    companion object {
        const val MODEL_PATH = "/data/local/tmp/llm/gemma-4-E2B-it.litertlm"
        // 音声会話向け: 短く話し言葉で返す
        const val SYSTEM_INSTRUCTION =
            "あなたは音声会話アシスタントです。日本語で、2文以内の短い話し言葉で答えてください。"

        fun modelAvailable(): Boolean = File(MODEL_PATH).canRead()
    }

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    /** モデルロード。最大 10 秒程度かかる。 */
    fun initialize() {
        check(modelAvailable()) { "model not found: $MODEL_PATH" }
        val e = Engine(EngineConfig(modelPath = MODEL_PATH, backend = Backend.CPU()))
        e.initialize()
        engine = e
        conversation = e.createConversation(
            ConversationConfig(systemInstruction = Contents.of(SYSTEM_INSTRUCTION))
        )
    }

    /** 同期テキスト対話。応答テキストを返す。 */
    fun ask(prompt: String): String {
        val c = conversation ?: error("not initialized")
        return c.sendMessage(prompt).toString()
    }

    /** 会話履歴をリセットする（barge-in 後の再開等で使用、Issue #22）。 */
    fun resetConversation() {
        conversation?.close()
        conversation = engine?.createConversation(
            ConversationConfig(systemInstruction = Contents.of(SYSTEM_INSTRUCTION))
        )
    }

    fun close() {
        conversation?.close()
        engine?.close()
        conversation = null
        engine = null
    }
}
