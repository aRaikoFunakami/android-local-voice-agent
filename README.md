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
| STT | sherpa-onnx 1.13.5 + SenseVoice int8（**2024-07-17 版に固定**、日本語）+ Silero VAD v5 |
| TTS | sherpa-onnx + supertonic-3-ja int8（日本語。Kokoro は sherpa-onnx に日本語 lexicon がなく不採用） |
| LLM | LiteRT-LM 0.16.0 + Gemma 4 E2B（端末上実行） |

## リポジトリ構成（2 リポジトリ）

- **このリポジトリ**: Android アプリ、STT/TTS/LLM 統合、ビルドスクリプト、ドキュメント、全 Issue 管理
- **[aRaikoFunakami/libwebrtc](https://github.com/aRaikoFunakami/libwebrtc)**: WebRTC fork。`local-audio` ブランチの `local_audio/` に APM ラッパ + JNI（upstream ファイル無改変）

## ドキュメント

- [開発計画書](docs/local-audio-engine-plan.md) — ソースツリー検証済みの全体計画
- [Issue 一覧](../../issues) — 依存関係つき。開発は worktree + PR + セルフレビュー方式

## テスト方法

このアプリは製品UIではなく、Issue単位で積み上げた**デバッグ用テストハーネス**です。
`MainActivity` に各パイプラインの単体テストボタンと、自動テスト用の intent extra が同居しています。

### 0. 前提: ビルド

```bash
# 1) sherpa-onnx ランタイム（必須、47MB、gitignore対象なので毎回取得）
./scripts/fetch_sherpa_onnx.sh

# 2) WebRTC ネイティブエンジン（.so、gitignore対象）
#    scripts/build_env.sh でコンテナ起動済みが前提（詳細は docs/local-audio-engine-plan.md）
./scripts/build_webrtc_android.sh

# 3) local.properties（Android Studio が自動生成しない場合）
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
```

Android Studio で開く場合、上記 1)・2) を先に済ませてから **Sync Project with Gradle Files** すること。
`.aar`/`.so` が無いまま sync すると `Null extracted folder for artifact...` のようなエラーになる。

会話ループ（LLM/STT/TTS）まで試すには、モデルも取得して端末へ push する:

```bash
./scripts/fetch_gemma.sh        # Gemma 4 E2B, 2.6GB（adb接続中なら自動push）
./scripts/fetch_stt_models.sh   # SenseVoice + Silero VAD
./scripts/fetch_supertonic.sh   # supertonic-3-ja
```

### 1. 一番シンプルな使い方

**実機**なら、アプリを起動してマイク権限を許可し、画面の **「会話開始」** ボタンを1回タップするだけ。
あとはマイクに話しかければ STT→LLM→TTS で返事が返ってくる。AI発話中に話しかければ barge-in（割り込み）も動く。

### 2. 画面のボタン一覧

| ボタン | 内容 |
|---|---|
| 上部テキスト | マイク権限・エンジン/sherpa-onnx ロード状態（タップで権限再要求） |
| LLM入力欄 + 送信 | テキストを Gemma に送って応答表示（音声は通さない） |
| TTS | 入力欄の文章を音声合成 → WAV ファイル保存（再生はしない） |
| Capture開始/停止 | マイク→APM→クリーンPCM のパイプライン単体テスト |
| Render開始/停止 | 無音（または440Hzトーン）を AEC 参照経由でスピーカー再生 |
| STT開始/停止 | Capture 併用で音声認識を動かす |
| Loopback開始/停止 | マイクで拾った音をそのまま AEC 経由でスピーカーに返す（ハウリングテスト用） |
| delay欄 | AEC の stream delay(ms) を手入力で設定 |
| **会話開始/停止** | 本命。STT→LLM→TTS の会話ループを起動 |

### 3. エミュレータでテストする場合の注意（マイク）

**Android Studio の Device Manager 経由でエミュレータを起動し、Extended Controls →
Microphone → 「Enable Host Microphone Access」を ON にすること。** さらに macOS 側で
Terminal / Android Studio にマイクのプライバシー許可（システム設定 → プライバシーとセキュリティ →
マイク）が必要。これが欠けていると `coreaudio: Could not initialize record` で仮想マイクが
無音のまま固まる（コマンドラインの `emulator` を直接ヘッドレス起動した場合もこの設定は反映されない
ことがあるため、まず Device Manager から GUI 経由で一度起動して確認するのが確実）。

動作確認方法:
```bash
adb shell am start -n com.example.localvoiceagent/.MainActivity --ez capture true --ez dump true
# 数秒間マイクに向かって話す → 停止 → raw_capture.pcm を pull してRMSが無音でないか確認
```

### 4. WAV注入によるテスト（マイクが使えない環境向け）

実機なしでも、任意の音声ファイルを APM の手前（マイク経路）に注入して会話をテストできる:

```bash
# 会話開始
adb shell am start -n com.example.localvoiceagent/.MainActivity --ez convo true --ei delay 20

# 質問WAV（48kHz mono int16）を注入（--activity-single-top で実行中の会話へ配送）
adb push your_question.wav /data/local/tmp/q1.wav
adb shell am start -n com.example.localvoiceagent/.MainActivity \
  --activity-single-top --es injectwav /data/local/tmp/q1.wav
```

質問WAVが手元になければ、まず TTS で自作できる:
```bash
adb shell am start -n com.example.localvoiceagent/.MainActivity --es tts "一番高い山は何ですか？"
# → tts_debug.wav が生成される。pull して 48kHz へリサンプルしてから injectwav で使う
```

### 5. 会話ログの確認

画面のログ欄は小さく見づらいので、logcat で見るのが確実:

```bash
adb logcat -s VoiceAgent
```

`state=LISTENING/THINKING/SPEAKING` の状態遷移、`You:`/`AI:` の発話内容、barge-in発火が出力される。

### 6. 主な自動テスト用 intent extra 一覧

| extra | 型 | 効果 |
|---|---|---|
| `capture` | bool | Capture パイプライン自動起動 |
| `render` | bool | Render パイプライン自動起動 |
| `tone` | bool | Render のデフォルト音源を440Hzトーンに（既定は無音） |
| `loopback` | bool | Capture→Render のループバック自動起動 |
| `stt` | bool | STT を Capture と共に自動起動 |
| `convo` | bool | 会話ループを自動開始（モデルロード完了後） |
| `dump` | bool | raw/aec/render の PCM ダンプを有効化 |
| `delay` | int | AEC stream delay (ms) |
| `echosim` | bool | 合成 echo 注入（AEC のオフライン評価用、`docs/aec_evaluation.md` 参照） |
| `echogain` / `echodelay` | float / int | 合成 echo のゲイン・遅延 |
| `tts` | string | 文章を合成し WAV 保存 |
| `say` | string | 文章を合成して実際に再生（render 経路） |
| `prompt` | string | LLM へ直接質問（モデルロード完了後） |
| `injectwav` | string | 指定 WAV を音声入力として注入 |

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
