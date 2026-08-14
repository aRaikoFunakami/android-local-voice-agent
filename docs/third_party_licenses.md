# サードパーティライセンス一覧

## ネイティブライブラリ（liblocal_audio_engine.so）

実際にリンクされた dependency から `scripts/generate_notices.sh`（WebRTC の
`tools_webrtc/libs/generate_licenses.py`）で機械生成する。生成結果:
[webrtc_third_party_licenses.md](webrtc_third_party_licenses.md)

対象（2026-08-14 生成、WebRTC branch-heads/7300 + local_audio）:
webrtc, abseil-cpp, compiler-rt, cpu_features, fft, libc++, libc++abi,
libunwind, llvm-libc, ooura, perfetto, pffft, protobuf, rnnoise, spl_sqrt_floor

WebRTC 本体: [webrtc-LICENSE](webrtc-LICENSE)（BSD 3-Clause）+
[webrtc-PATENTS](webrtc-PATENTS)

**WebRTC revision 更新時は `scripts/generate_notices.sh` を再実行し、
本ファイルと生成物の差分をレビュー対象にすること。**

## アプリ依存ライブラリ

| コンポーネント | バージョン | ライセンス |
|---|---|---|
| sherpa-onnx | 1.13.5 | Apache-2.0 |
| onnxruntime（sherpa-onnx 同梱） | - | MIT |
| LiteRT-LM (`litertlm-android`) | 0.16.0 | Apache-2.0 |
| androidx.core | 1.16.0 | Apache-2.0 |

## モデル（アプリ非同梱・初回配置方式）

| モデル | 配布元 | ライセンス |
|---|---|---|
| Gemma 4 E2B (`gemma-4-E2B-it.litertlm`) | litert-community (HF) | Gemma Terms of Use |
| Supertonic-3 TTS (int8) | k2-fsa/sherpa-onnx releases | MIT（パッケージ同梱 LICENSE: Copyright 2025 Supertone Inc.） |
| STT モデル（SenseVoice 等、Issue #18 で確定） | k2-fsa | 確定時に追記 |

モデルは APK に同梱しないため NOTICE 表示義務の対象外だが、配布形態を変える場合は再確認すること。
