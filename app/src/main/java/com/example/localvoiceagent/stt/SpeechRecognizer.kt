package com.example.localvoiceagent.stt

/**
 * STT backend 境界（開発計画 §9）。
 * acceptAudio は audio thread から呼ばれるため、実装は内部 queue に積むだけで
 * inference は専用 worker で行うこと（計画 §12）。
 */
interface SpeechRecognizer {
    /** AEC 済み 48kHz mono int16 PCM を渡す。 */
    fun acceptAudio(samples: ShortArray, sampleRate: Int)

    /** 認識セグメント確定時のコールバック（worker thread 上で呼ばれる）。 */
    var onFinalResult: ((String) -> Unit)?

    /** 発話中（VAD が speech を検出中）か。barge-in 判定（#22）に使う。 */
    fun isSpeechActive(): Boolean

    fun reset()
    fun close()
}
