# Issue ドラフト（android-local-voice-agent）

作成順 = Issue 番号。`Depends:` は先行 Issue 番号。依存グラフは末尾。

対象リポジトリ:
- **app** = `aRaikoFunakami/android-local-voice-agent`（Issue は全部ここ）
- **fork** = `aRaikoFunakami/libwebrtc`（`local-audio` ブランチ、`local_audio/` のみ追加）

---

## #1 [infra] app リポジトリ初期化・スキャフォールド
Labels: `infra`, `phase-0`
Depends: なし

**目的**: プロジェクトの器を作る。

**作業内容**:
- README（プロジェクト概要、アーキテクチャ図、2 リポジトリ構成の説明）
- `docs/local-audio-engine-plan.md`（開発計画書一式）
- `.gitignore`（Android/Gradle/モデルファイル）
- ディレクトリ骨格: `app/`, `native-api/`, `scripts/`, `docker/`, `docs/`
- LICENSE（Apache-2.0 等自リポジトリ分）+ NOTICE 方針の記載

**完了条件**: main に初期コミットが入り README から計画書へ辿れる。

## #2 [infra] Colima x64 + depot_tools ビルド環境
Labels: `infra`, `phase-0`
Depends: なし

**目的**: WebRTC の Android ビルドは x86_64 Linux 限定（DEPS の clang が Linux_x64 のみ）。Apple Silicon Mac 上に Rosetta 経由の x64 Linux 環境を作る。

**作業内容**:
- `colima start --profile x64 --arch x86_64 --vm-type vz --vz-rosetta --cpu 6 --memory 16 --disk 150`
- depot_tools + ビルド依存（python3, curl 等）入りの Docker イメージ `docker/webrtc-build/Dockerfile`
- `scripts/build_env.sh`（colima 起動 → コンテナ起動のラッパ）

**完了条件**: コンテナ内で `uname -m` が `x86_64`、`gclient --version` が動く。

## #3 [fork] release-7300 / local-audio ブランチ構成
Labels: `fork`, `phase-0`
Depends: なし

**目的**: revision 固定。main HEAD 追従を禁止し upstream リリースブランチ起点にする。

**作業内容**:
- upstream `refs/branch-heads/7300` を fork へ `release-7300` として push
- `release-7300` 起点で `local-audio` ブランチ作成・push
- `docs/webrtc_revision.md` に WEBRTC_COMMIT / ブランチ運用ルールを記録（app repo 側）

**完了条件**: fork に `release-7300` / `local-audio` が存在し、起点 commit が記録されている。

## #4 [infra] gclient checkout + gn gen 疎通（Phase 0a 前半）
Labels: `infra`, `phase-0`
Depends: #2, #3

**目的**: fork を指す gclient checkout で Android arm64 のビルド構成が生成できることを確認。

**作業内容**:
- `.gclient` テンプレート（url = fork の `local-audio` 固定 commit、`target_os = ["android"]`）
- コンテナ内で `gclient sync`（~16GB、キャッシュ方針も記録）
- `gn gen out/android_arm64 --args='target_os="android" target_cpu="arm64" is_debug=false rtc_include_tests=false rtc_build_examples=false rtc_enable_protobuf=false android_static_analysis="off"'`
- 使用 NDK バージョン・gn args を `docs/webrtc_revision.md` へ追記

**完了条件**: `gn gen` が成功し `ninja -C out/android_arm64 modules/audio_processing` がリンクまで通る。

## #5 [fork] local_audio/ hello-world rtc_shared_library
Labels: `fork`, `phase-0`
Depends: #4

**目的**: upstream 無改変のまま独自 `.so` を GN ツリー内でビルドできることを証明する。

**作業内容**:
- `local_audio/BUILD.gn` に `rtc_shared_library("local_audio_engine")`（最小: バージョン文字列を返す関数のみ、APM 未リンク）
- `ninja -C out/android_arm64 local_audio:local_audio_engine` で `liblocal_audio_engine.so` 生成
- ビルド手順を `scripts/build_webrtc_android.sh` に落とす

**完了条件**: arm64-v8a の `.so` が生成され、`file`/`readelf` で確認できる。upstream ファイルへの変更ゼロ。

## #6 [app] Android アプリスケルトン
Labels: `app`, `phase-0`
Depends: #1

**目的**: アプリの器。以降の全 app 側 Issue の土台。

**作業内容**:
- Kotlin + Gradle(KTS) の最小アプリ（単一 Activity、フォアグラウンドサービスは後続）
- `RECORD_AUDIO` 権限のリクエストフロー
- `minSdk 31` / `targetSdk` は SDK 手持ちに合わせる / abiFilters `arm64-v8a`
- `jniLibs/arm64-v8a/` 配置規約と `native-api/` ヘッダ置き場

**完了条件**: エミュレータで起動し権限取得できる APK がビルドできる。

## #7 [app] .so ロードスモーク（Phase 0a 完了）
Labels: `app`, `phase-0`
Depends: #5, #6

**目的**: Phase 0a の完了条件「APK へロードできる」を満たす。

**作業内容**:
- #5 の `.so` を `jniLibs` に配置、`System.loadLibrary` + バージョン文字列表示
- `.so` の受け渡し手順（ビルド成果物 → app repo）を scripts 化

**完了条件**: エミュレータ上でネイティブ関数呼び出しが成功しバージョン文字列が UI に出る。

## #8 [infra] NOTICE/ライセンス生成パイプライン（Phase 0b）
Labels: `infra`, `phase-0`
Depends: #4

**目的**: リンクされた dependency 集合に対応するライセンス表記を機械生成する。

**作業内容**:
- `tools_webrtc/libs/generate_licenses.py` を `local_audio:local_audio_engine` target に対して実行する `scripts/generate_notices.sh`
- 生成物（LICENSE.md）を app repo の `docs/third_party_licenses.md` に取り込み
- WebRTC LICENSE / PATENTS の同梱

**完了条件**: NOTICE が生成され、revision 更新時に差分レビューする手順が README に記載されている。

## #9 [fork] AudioFrameBuffer（10ms framing ring buffer）
Labels: `fork`, `phase-1`
Depends: #5

**目的**: AudioRecord の read サイズと APM の 480 samples/10ms を吸収する固定長 ring buffer。

**作業内容**:
- `local_audio/audio_frame_buffer.{h,cc}`: 事前確保・lock-free（SPSC）・alloc なしの push/pop
- GN unit test target（Linux ホスト実行可）

**完了条件**: 境界条件（端数 push、空 pop、wrap-around）のテストが通る。audio path で heap alloc なし。

## #10 [fork] LocalAudioProcessor: APM/AEC3/NS/AGC2 ラップ
Labels: `fork`, `phase-1`
Depends: #5

**目的**: WebRTC API をアプリへ露出させない境界クラス本体。

**作業内容**:
- `local_audio/local_audio_processor.{h,cc}`:
  - `Initialize(AudioProcessorConfig)` → `BuiltinAudioProcessingBuilder(config).Build(CreateEnvironment())`
  - `EchoCanceller3Factory(EchoCanceller3Config)` 注入経路（チューニング用）
  - AEC3（`echo_canceller.enabled=true, mobile_mode=false`）/ NS / **AGC2**（AGC1 は削除予定のため不使用）/ HPF の on/off
  - `ProcessCapture` / `ProcessRender`（int16, 48kHz mono 10ms 固定）/ `SetStreamDelayMs` / `Reset`
- 48kHz/mono/480 samples 以外は Initialize で拒否

**完了条件**: ヘッダに WebRTC 型が一切現れない。`.so` がビルドされサイズ・シンボルを確認。

## #11 [fork] Linux ホスト WAV オフラインテスト（Phase 1 完了）
Labels: `fork`, `phase-1`
Depends: #9, #10

**目的**: 実機に行く前に AEC が机上で成立することを証明する（Phase 1 完了条件）。

**作業内容**:
- `local_audio/test/offline_wav_test.cc`: render WAV + capture WAV（echo 合成入り）→ `ProcessReverseStream`/`ProcessStream` → 出力 WAV
- Linux ホスト（x64 コンテナ内）でビルド・実行。ASan/LSan 適用
- `audioproc_f`（`rtc_include_tests=true` の別 out dir）と同一入力での突き合わせ
- AEC on/off、NS on/off の切り替え確認、長時間（10 分相当）連続処理

**完了条件**: echo 合成入力で AEC on 時に出力エネルギーが明確に減衰（ERLE 目安を記録）。リークなし。crash なし。

## #12 [fork] JNI bridge
Labels: `fork`, `phase-1`
Depends: #10

**目的**: Kotlin から 5 操作だけを見せる。

**作業内容**:
- `local_audio/local_audio_jni.cc`: `create` / `processCapture` / `processRender` / `setStreamDelayMs` / `destroy`（+ `getVersion`）
- DirectByteBuffer 前提で JNI 呼び出しごとの heap alloc ゼロ
- `native-api/LocalAudioEngine.kt`（external 宣言 + 薄いラッパ）を app repo へ

**完了条件**: `.so` に JNI シンボルが揃い、Kotlin 側宣言と署名が一致（javah 相当で検証）。

## #13 [app] Capture パイプライン
Labels: `app`, `phase-2`
Depends: #7, #12

**目的**: マイク → APM → clean PCM の実機経路（原案 §5）。

**作業内容**:
- Capture thread: `AudioRecord`（48kHz mono, `VOICE_RECOGNITION` source）→ ring buffer → 10ms → `processCapture` → PCM queue
- debug PCM sink（`raw_capture.pcm` / `aec_output.pcm`、debug ビルド + 明示 opt-in のみ）
- UI thread から `processCapture` を呼ばない。callback path で alloc/ログ大量出力なし

**完了条件**: エミュレータで 60 秒キャプチャして dump が壊れていない（framing 崩れなし、drop カウント 0 継続）。

## #14 [app] Render パイプライン
Labels: `app`, `phase-2`
Depends: #7, #12

**目的**: 再生音を必ず AEC の reverse stream に通す経路（原案 §6）。

**作業内容**:
- Render thread: test PCM（合成トーン/固定 WAV）→ 10ms framing → `processRender` → `AudioTrack`
- `render_reference.pcm` debug dump
- underrun カウンタ

**完了条件**: エミュレータで連続再生 60 秒、underrun 継続発生なし、reverse stream 投入と再生が同一データ。

## #15 [app] フルループバック + 安定性（Phase 2 完了）
Labels: `app`, `phase-2`
Depends: #13, #14

**目的**: Phase 2 完了条件の充足。

**作業内容**:
- capture → APM → (モニタ用) AudioTrack のループバックモード
- `setStreamDelayMs` の設定 UI（定数埋め込み禁止、原案 §7）
- 画面 off/on、audio route 変更（エミュレータで可能な範囲）での再初期化
- underrun / queue depth / drop の統計表示

**完了条件**: 10 分連続動作で capture/render underrun が継続発生しない。10ms framing が崩れない。

## #16 [app] AEC 評価ツーリング + debug dump（Phase 3）
Labels: `app`, `phase-3`
Depends: #15

**目的**: AEC 性能を耳ではなく数値で評価する装置を作る（原案 §18、Phase 3）。

**作業内容**:
- 4 種 PCM 同時 dump（render reference / raw capture / AEC output / STT input）
- far-end only / near-end only / double-talk / silence のテストシナリオ再生機能
- 採取 PCM を `audioproc_f` に食わせる机上評価スクリプト + ERLE 計算スクリプト
- **制約の明文化**: エミュレータはスピーカー→マイクの実音響経路を持たないため、AEC の音響性能評価は実機必須。本 Issue はツーリング完成 + エミュレータ上での配管検証（ループバック合成 echo）まで。実機評価は別 Issue（未着手のまま残す）

**完了条件**: 合成 echo（render を capture に加算）で ERLE が算出でき、AEC on/off の差が数値で出る。実機評価手順書が docs にある。

## #17 [app] sherpa-onnx ランタイム導入
Labels: `app`, `phase-4`
Depends: #6

**目的**: STT/TTS 共通ランタイムを 1 度だけ導入する。

**作業内容**:
- sherpa-onnx の Android ライブラリ導入（Maven AAR があれば優先、なければ prebuilt jniLibs）。バージョン固定
- モデルファイル管理方針: APK 同梱ではなく初回配置（`adb push` / ダウンロードスクリプト）。`.gitignore` 済み
- ライセンス（Apache-2.0）を NOTICE へ追記

**完了条件**: アプリから sherpa-onnx のバージョンが取得でき、モデルなしでも安全に起動する。

## #18 [app] STT: 日本語認識 + VAD 接続（Phase 4）
Labels: `app`, `phase-4`
Depends: #15, #17

**目的**: AEC output → テキスト。

**作業内容**:
- `SpeechRecognizer` interface（原案 §9）+ sherpa-onnx 実装（SenseVoice ja。ストリーミング要件次第で Zipformer ja に切替可、選定理由を記録）
- AEC output 48kHz → 16kHz resample（`PushResampler` は engine 側、または sherpa-onnx 側機能。二重リサンプル禁止）
- VAD（WebRTC VAD で開始、sherpa-onnx 同梱 Silero VAD への差し替え口を確保）
- STT worker thread（audio thread で inference 禁止、原案 §12）

**完了条件**: エミュレータのホストマイクから日本語発話が partial/final テキスト化される。連続発話で buffer overflow なし。

## #19 [app] TTS: Kokoro-82M 単体（Phase 5 前半）
Labels: `app`, `phase-5`
Depends: #17

**目的**: 日本語テキスト → PCM。

**作業内容**:
- `SpeechSynthesizer` interface（原案 §10）+ sherpa-onnx Kokoro-82M（日本語対応モデル）実装
- `AudioSink`: PCM / sample rate / channels / end-of-stream
- 生成 PCM を WAV 保存するデバッグ経路（AudioTrack 直結は禁止のまま）

**完了条件**: 任意の日本語文から PCM が生成され WAV で検聴できる。RTF（実時間比）を記録。

## #20 [app] TTS render 統合（Phase 5 完了）
Labels: `app`, `phase-5`
Depends: #15, #19

**目的**: TTS 出力を AEC reference と同一経路でスピーカーへ（原案 §10, Phase 5）。

**作業内容**:
- TTS native rate → 48kHz resample → 10ms framing → `processRender` + `AudioTrack`（#14 の render 経路に接続）
- 連続複数発話、発話中断（キャンセル）対応

**完了条件**: TTS 再生開始/終了/連続発話で AEC 状態が破綻しない（配管上の検証。音響評価は実機 Issue へ）。

## #21 [app] LLM: LiteRT-LM + Gemma E2B テキスト対話単体
Labels: `app`, `phase-6`
Depends: #6

**目的**: 端末上 LLM のテキスト in/out を単体で成立させる。

**作業内容**:
- LiteRT-LM ランタイム導入 + Gemma E2B（`.litertlm`）。**Gemma 4 E2B が入手不可の場合は Gemma 3n E2B にフォールバックし、その旨を Issue コメントに記録**
- Inference worker thread、システムプロンプト（音声会話向け短文応答）
- テキスト入力 → 応答表示のデバッグ UI
- モデルは初回配置方式（#17 と同じ）。エミュレータでの RAM 要件（AVD 8GB 推奨）を README に記録

**完了条件**: エミュレータ上で日本語テキスト対話が成立。応答レイテンシを記録。

## #22 [app] Conversation Controller + barge-in（Phase 6）
Labels: `app`, `phase-6`
Depends: #18, #20, #21

**目的**: STT → LLM → TTS のループと割り込み（原案 §11, §16 Phase 6）。

**作業内容**:
- 状態機械: Idle → Listening → Thinking → Speaking →（barge-in で）Listening
- TTS 再生中の VAD 検出で TTS キャンセル + LLM 生成中断
- 自己 TTS を会話入力として再認識しないためのガード（AEC + Speaking 中の STT 結果の扱い）
- ネットワーク権限なし（Manifest に INTERNET を含めない）で全機能動作

**完了条件**: AI 発話中のユーザー発話が STT に到達し会話が切り替わる（TTS 停止だけでは合格にしない、原案 §11）。

## #23 [app] E2E エミュレータ検証 + エビデンス（開発終了）
Labels: `app`, `phase-6`, `evidence`
Depends: #8, #16, #22

**目的**: 開発終了判定。

**作業内容**:
- エミュレータ（arm64-v8a, RAM 8GB）で: 起動 → 権限 → 発話 → STT → LLM 応答 → TTS 再生 → barge-in の一連を実施
- エビデンス取得: スクリーンショット、logcat、デバッグ統計（underrun/queue/drop）、PCM dump、動作記録を `docs/evidence/` に保存
- 10 分以上の連続会話ラン
- 残制約（実機 AEC 音響評価が未実施であること）を README に明記

**完了条件**: エビデンス一式がリポジトリに入り、全 Issue がクローズされている。

---

## 依存グラフ

```
依存なし（即時並列開始可）: #1, #2, #3
#1 ─→ #6 ─→ #17 ─→ #18, #19
   │      └→ #21
#2 ─┬→ #4 ─┬→ #5 ─┬→ #7（← #6 も）
#3 ─┘      └→ #8  ├→ #9 ─┐
                  └→ #10 ─┼→ #11
                       └──┼→ #12 ─→ #13, #14（← #7 も）─→ #15
#15 ─→ #16, #18, #20（← #19 も）
#18 + #20 + #21 ─→ #22 ─→ #23（← #8, #16 も）
```

クリティカルパス: #2/#3 → #4 → #5 → #10 → #12 → #13/#14 → #15 → #18/#20 → #22 → #23
並列トラック: sherpa-onnx（#17-#19, #21）は #6 さえ済めばビルド環境と独立に進行可。
