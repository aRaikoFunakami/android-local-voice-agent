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

Issue #1〜#23 の開発完了（2026-08-14）。各 Issue は worktree + PR + セルフレビュー方式で
マージ済み。E2E 動作エビデンスは [docs/evidence/e2e-2026-08-14/](docs/evidence/e2e-2026-08-14/) を参照。

**エミュレータで検証済み**: 機内モード下のフル会話ループ（マイク経路→APM/AEC3→SenseVoice STT→
Gemma 4 E2B→supertonic-3-ja TTS→AEC 参照つき再生）、barge-in、10 分連続動作、合成 echo での
ERLE 33.8dB。

**実機での後続評価が必要な項目**（エミュレータでは原理的に検証不能）:
- 実スピーカー→マイク音響経路での AEC 品質（手順: [docs/aec_evaluation.md](docs/aec_evaluation.md)）
- EchoCanceller3Config の実機チューニング（注入口は実装済み）
- audio route 変更（ヘッドセット抜挿）の再初期化
- 実機 CPU/NPU での TTS RTF・LLM レイテンシ再測定

## ライセンス

このリポジトリのコードは [Apache-2.0](LICENSE)。

本プロジェクトは以下のサードパーティを利用する。NOTICE は実際にリンクされた dependency から機械生成する（Issue #8）:

- WebRTC (BSD 3-Clause + PATENTS) — `tools_webrtc/libs/generate_licenses.py` で GN dependency graph から生成
- sherpa-onnx (Apache-2.0)
- LiteRT-LM (Apache-2.0) / Gemma (Gemma Terms of Use)

WebRTC revision 更新時は NOTICE 差分をレビュー対象とする。
