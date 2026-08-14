package com.example.localvoiceagent

import com.example.localvoiceagent.stt.SpeechRecognizer
import com.example.localvoiceagent.tts.TtsPlayer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 会話状態機械（開発計画 §11, §16 Phase 6）:
 *   Idle → Listening → Thinking → Speaking → Listening（barge-in で即 Listening）
 *
 * - AI 発話中（Speaking）も STT は動き続ける。AEC が自己音声を除去するため、
 *   Speaking 中に VAD が speech を検出したらユーザーの割り込みとみなし
 *   TTS を即キャンセルする（TTS 停止だけでなく、その発話は STT に到達し
 *   次のターンとして処理される = 計画 §11 の合格条件）。
 * - LLM 推論は inference worker（audio thread では実行しない、§12）。
 */
class ConversationController(
    private val stt: SpeechRecognizer,
    private val llm: LlmEngine,
    private val ttsPlayer: TtsPlayer,
    /** UI へ状態/会話ログを通知（任意 thread から呼ばれる） */
    private val onEvent: (state: String, line: String?) -> Unit,
) {
    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    private val state = AtomicReference(State.IDLE)
    val bargeInCount = AtomicLong()

    // barge-in は瞬間ノイズ/AEC 残差で誤発火しないよう、連続 N 回（50ms×4=200ms）
    // の speech 検出を要求する
    private var speechStreak = 0

    // AEC 残差の断片（「そ。」等）をユーザー発話として扱わない最小文字数
    private val minUtteranceChars = 3

    private val worker = Executors.newSingleThreadExecutor()
    // barge-in 監視: Speaking 中のみ 50ms 周期で VAD を見る
    private val watchdog = Executors.newSingleThreadScheduledExecutor()

    fun state(): State = state.get()

    fun start() {
        if (!state.compareAndSet(State.IDLE, State.LISTENING)) return
        stt.onFinalResult = { text -> onUserUtterance(text) }
        watchdog.scheduleWithFixedDelay({
            if (state.get() == State.SPEAKING && stt.isSpeechActive()) {
                if (++speechStreak >= 4) {  // 200ms 持続で barge-in 成立
                    speechStreak = 0
                    ttsPlayer.cancel()
                    bargeInCount.incrementAndGet()
                    setState(State.LISTENING, "（barge-in: AI発話を中断）")
                }
            } else {
                speechStreak = 0
                if (state.get() == State.SPEAKING && !ttsPlayer.isSpeaking()) {
                    setState(State.LISTENING, null)  // 再生し切った
                }
            }
        }, 100, 50, TimeUnit.MILLISECONDS)
        setState(State.LISTENING, "会話開始")
    }

    fun stop() {
        state.set(State.IDLE)
        ttsPlayer.cancel()
        stt.onFinalResult = null
        watchdog.shutdown()
        worker.shutdown()
    }

    private fun onUserUtterance(text: String) {
        // AEC 残差などの断片は会話ターンにしない（句読点を除いた実質文字数で判定）
        val effective = text.replace(Regex("[。、．，！？!?\\s]"), "")
        if (effective.length < minUtteranceChars) {
            onEvent(state.get().name, "（断片を無視: $text）")
            return
        }
        when (state.get()) {
            State.LISTENING, State.SPEAKING -> ask(text)
            // Thinking 中の発話は現ターン優先で無視（v1 の割り切り）
            else -> onEvent(state.get().name, "（無視: $text）")
        }
    }

    private fun ask(text: String) {
        setState(State.THINKING, "You: $text")
        worker.execute {
            val reply = runCatching { llm.ask(text) }
                .getOrElse { "すみません、うまく考えられませんでした。" }
            if (state.get() == State.IDLE) return@execute
            setState(State.SPEAKING, "AI: $reply")
            ttsPlayer.speak(reply)
        }
    }

    private fun setState(s: State, line: String?) {
        state.set(s)
        onEvent(s.name, line)
    }
}
