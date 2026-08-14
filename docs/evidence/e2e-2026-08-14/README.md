# E2E 動作確認エビデンス（2026-08-14）

## 実行環境

| 項目 | 値 |
|---|---|
| デバイス | Android Emulator AVD `VoiceAgent`（Pixel 7 profile, android-36 google_apis arm64-v8a, RAM 8GB, data 16GB） |
| ホスト | Apple Silicon Mac（emulator は arm64 ネイティブ実行） |
| APK | app-debug（61MB, arm64-v8a のみ） |
| ネットワーク | **機内モード ON**（`airplane_mode_on=1`）、Manifest に INTERNET 権限なし（dumpsys で確認） |
| ネイティブエンジン | `liblocal_audio_engine.so`（WebRTC branch-heads/7300 + local_audio, sha256 `e0fa76f8…`） |
| STT | sherpa-onnx 1.13.5 + SenseVoice int8 2024-07-17（ja）+ Silero VAD v5 |
| TTS | sherpa-onnx + supertonic-3 int8（ja, 44.1kHz） |
| LLM | LiteRT-LM 0.16.0 + Gemma 4 E2B（CPU, 2.6GB litertlm） |
| AEC 評価 | 合成 echo 注入（delay 20ms / gain 0.5、エミュレータに音響経路がないため） |

## 実施項目と結果

各ファイルは本ディレクトリに同梱。

| 項目 | 結果 | エビデンス |
|---|---|---|
| 起動・権限・エンジンロード | ✅ | `final_screen.png`（engine 0.2 / sherpa loaded / smoke OK） |
| 音声→STT→LLM→TTS の会話ターン | ✅ | `voiceagent_logcat.txt`（You:/AI: の対） |
| barge-in（AI 発話中の割り込み） | ✅ | 同上（`barge-in: AI発話を中断` → 割り込み発話が新ターン化） |
| 自己 TTS の再認識ループなし | ✅ | 同上（残差断片は `断片を無視` で破棄、ループなし） |
| 機内モードで全機能動作 | ✅ | 本ラン全体が機内モード下 |
| 10 分以上の連続会話 | ✅ | `voiceagent_logcat.txt`（18:35:23〜18:45:55、17 ターン・barge-in 4 回・断片破棄 2 回） |
| audio パイプライン安定性（10 分連続） | ✅ | `loopback_10min_stats.png`（Issue #15 実測: 62679/62700 frames、underrun/エラー 0、画面 off/on 込み） |
| AEC 動作（合成 echo） | ✅ | `erle_result.txt`（Issue #16 専用セッション実測: **ERLE 33.8dB**） |

## 既知の残制約（実機での後続評価が必要）

1. **実音響経路の AEC 評価は未実施**（エミュレータには speaker→mic 経路がない）。
   手順は [docs/aec_evaluation.md](../../aec_evaluation.md) に整備済み。実機で §17 マトリクス
   （音量 4 段階 × 設置 2 × 環境 2 × 発話 4 ケース）と delay スイープを実施すること。
2. 合成 echo（無響・完全コピー）条件では AEC 残差による誤 barge-in が発生しうる。
   実機では EchoCanceller3Config チューニング（注入口実装済み）で追い込む。
3. TTS RTF ≈ 1.1（エミュレータ CPU）。実機 NPU/スレッド数で要再測。
4. audio route 変更（ヘッドセット抜挿）はエミュレータで検証不能。
