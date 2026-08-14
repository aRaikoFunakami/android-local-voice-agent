# android-local-voice-agent

AOSP/Android arm64-v8a 上で**ネットワーク通信を一切使わず**、マイク入力からスピーカー出力までを端末内で完結させるボイス AI エージェント。

WebRTC の **Audio Processing Module (APM / AEC3)** を通信ライブラリとしてではなく**端末内音響前処理エンジン**として使い、エコーキャンセラが実装されていない AOSP 環境でも STT / TTS / barge-in（AI 発話中の割り込み）を成立させる。

```
Microphone → AudioRecord → WebRTC APM (AEC3/NS/AGC2) → VAD → STT → LLM → TTS
                                  ↑ ProcessReverseStream                  ↓
                                  └────────── render reference ←──── AudioTrack → Speaker
```

## 技術スタック

| 役割 | 実装 |
|---|---|
| 音響前処理 | WebRTC APM / AEC3（[aRaikoFunakami/libwebrtc](https://github.com/aRaikoFunakami/libwebrtc) fork の `local_audio/`） |
| STT | sherpa-onnx（SenseVoice / Zipformer 日本語） |
| TTS | sherpa-onnx + Kokoro-82M（日本語） |
| LLM | LiteRT-LM + Gemma E2B（端末上実行） |

## リポジトリ構成（2 リポジトリ）

- **このリポジトリ**: Android アプリ、STT/TTS/LLM 統合、ビルドスクリプト、ドキュメント、全 Issue 管理
- **[aRaikoFunakami/libwebrtc](https://github.com/aRaikoFunakami/libwebrtc)**: WebRTC fork。`local-audio` ブランチの `local_audio/` に APM ラッパ + JNI（upstream ファイル無改変）

## ドキュメント

- [開発計画書](docs/local-audio-engine-plan.md) — ソースツリー検証済みの全体計画
- [Issue 一覧](../../issues) — 依存関係つき。開発は worktree + PR + セルフレビュー方式

## 開発ステータス

Issue #1〜#23 を依存順に開発中。現状は [Issues](../../issues) を参照。
