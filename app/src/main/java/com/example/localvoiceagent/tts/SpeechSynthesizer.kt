package com.example.localvoiceagent.tts

/**
 * TTS backend 境界（開発計画 §10）。
 * 実装は PCM を AudioSink へ渡すだけで、AudioTrack へ直接書かない。
 * render 経路（48kHz resample → 10ms framing → processRender + AudioTrack）は Issue #20。
 */
interface AudioSink {
    /** 合成 PCM の通知。samples は int16 mono。複数回呼ばれうる。 */
    fun onAudio(samples: ShortArray, sampleRate: Int, channels: Int)

    /** 合成終了（end-of-stream）。 */
    fun onEnd()
}

interface SpeechSynthesizer {
    /** text を合成し sink へ流す。ブロッキング。worker thread から呼ぶこと。 */
    fun synthesize(text: String, sink: AudioSink)

    fun close()
}
