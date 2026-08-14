# Android ローカル音声AI向け WebRTC APM/AEC3 組み込み開発計画（検証済み完成版）

対象フォーク: `github.com/aRaikoFunakami/libwebrtc`（upstream: `webrtc.googlesource.com/src`、検証時 HEAD `5da5a6e4e0` / 2026-08-13）

## 0. 結論

**目標は達成可能。** アイデアの技術前提（APM の int16 10ms API、AEC3、`set_stream_delay_ms`、VAD、リサンプラ、ライセンス生成スクリプト）はすべて現行ソースツリーに実在することを確認した。ただし**ビルド戦略に 3 つの重大な前提修正が必要**（§2）。それ以外のアーキテクチャ・フェーズ計画・禁止事項は原案をほぼそのまま採用する。

## 1. ソースツリー検証結果

原案の引用（citeturn...）はすべて実ソースに対して照合済み。

| 原案の前提 | 結果 | 根拠（実ファイル） |
|---|---|---|
| int16 / StreamConfig 版 `ProcessStream` / `ProcessReverseStream` | ✅ | `api/audio/audio_processing.h:542,562` |
| `set_stream_delay_ms()` | ✅ | `api/audio/audio_processing.h:611` |
| AEC3 実装と設定注入 | ✅ | `modules/audio_processing/aec3/echo_canceller3.h`、`api/audio/echo_canceller3_factory.h:33`（`EchoCanceller3Factory(const EchoCanceller3Config&)`） |
| stream 系 API と `ProcessStream()` の並行呼び出し制約 | ✅ | `api/audio/audio_processing.h:69-70` |
| APM の GN target | ✅ | `modules/audio_processing/BUILD.gn:150` `rtc_library("audio_processing")` |
| ライセンス生成スクリプト | ✅ | `tools_webrtc/libs/generate_licenses.py` |
| WebRTC VAD（単体利用可） | ✅ | `common_audio/vad/include/webrtc_vad.h` |
| リサンプラ | ✅ | `common_audio/resampler/include/push_resampler.h`（`PushResampler`） |
| 共有ライブラリ用 GN テンプレート | ✅ | `webrtc.gni:1007` `template("rtc_shared_library")` |
| オフライン APM 評価ツール | ✅ | `modules/audio_processing/BUILD.gn:764` `audioproc_f_impl`（WAV 入出力で AEC を机上評価できる） |
| 安定リリースブランチ | ✅ | `refs/branch-heads/7300`, `refs/branch-heads/7204` を upstream に確認 |

## 2. 原案からの重大な修正点（3 + 2）

### 修正 1: Android ビルドは Linux ホスト限定、かつ x86_64 (amd64) 限定

`docs/native-code/android/README.md` 冒頭に明記: **"Android development is only supported on Linux."**

さらに `DEPS` を確認したところ、prebuilt clang は **`Linux_x64` パッケージしか存在しない**（`host_cpu` 分岐なしで `host_os == "linux"` のみが条件）:

```
'object_name': 'Linux_x64/clang-llvmorg-23-init-19482-g53d18800-2.tar.xz',
'condition': '(host_os == "linux" or checkout_android) and non_git_source',
```

`Linux_arm64` の clang パッケージは存在しない。つまり arm64 Linux ホスト（Apple Silicon 上の native arm64 コンテナ等）では x64 バイナリを QEMU 等でエミュレーション実行するしかなく、大量コンパイルには非現実的。**x86_64 (amd64) Linux ホストが実質必須**。（参考: `reclient`（Googleのリモートビルド高速化）も `not (host_os == "linux" and host_cpu == "arm64")` で arm64 Linux を明示的に除外しているが、ローカルビルドでは reclient を使わないため実害はない。）

現在の開発機は macOS（arm64）のため、そのままでは arm64-v8a 向けビルドは不可能。対応:

- **CI（優先）**: GitHub Actions の `ubuntu-latest` ランナーは x64 なのでそのまま動く。
- **ローカル反復**: Colima の Rosetta 経路（`--vm-type vz --vz-rosetta`）で x86_64 Linux VM を立てる。QEMU のフルエミュレーションより CPU 律速なコンパイルが大幅に高速（ほぼネイティブ相当）で、`local_audio:local_audio_engine`（APM 一式のみ、PeerConnection/動画コーデック抜き）程度の規模なら十分実用的:

```bash
colima start --profile x64 --arch x86_64 --vm-type vz --vz-rosetta \
  --cpu 6 --memory 16 --disk 150
docker context use colima-x64
```

`default`（arm64）プロファイルとは分ける。`--vm-type vz --vz-rosetta` を両方指定しないと Rosetta 経路が有効にならず QEMU 実行に落ちる点に注意。反復速度がそれでも問題化したら x64 の cloud VM をリモート開発機にする。
- CI は Android SDK/NDK 込みで checkout が **約 16GB** になるため、`gclient sync` 結果と `out/` を積極的にキャッシュする。
- macOS ではビルドしない。ただし **APM 自体はポータブルなので、Phase 1 のオフライン WAV テストは x64 Linux ホストバイナリで実行できる**（実機不要で反復が速い。§6 Phase 1 参照）。

### 修正 2: GitHub フォーク単体ではビルド不能（gclient/DEPS 構成が必須）

現在のローカルクローンおよび GitHub フォークは **git リポジトリ `src` のみ**であり、`buildtools/`、`build/`、`third_party/`（abseil 等）が存在しないことを確認済み。これらは `gclient sync` が `DEPS` に従って Google のインフラから取得する。つまり:

- フォークは「src の系譜を保持する場所」であり、ビルドは常に gclient checkout の中で行う。
- ビルド環境側の `.gclient` でフォークを指す:

```python
solutions = [{
    "name": "src",
    "url": "https://github.com/aRaikoFunakami/libwebrtc.git@<pinned-commit>",
    "deps_file": "DEPS",
    "managed": False,
}]
target_os = ["android"]
```

- `DEPS` は src 内にあるため、フォークの commit を固定すれば依存 revision も自動的に固定される（原案の revision 追跡方針はこの構成で満たされる）。

### 修正 3: revision 固定は main HEAD ではなく branch-heads を使う

現在フォークに push 済みの `main` は upstream main の 2026-08-13 スナップショットであり、リリースブランチではない。原案 §2 の「main 常時追従の禁止」を徹底するため:

- upstream の **`refs/branch-heads/7300`**（検証時点の最新リリースブランチ）をフォークへ push し、そこから開発ブランチを切る:

```bash
git fetch origin refs/branch-heads/7300:refs/heads/release-7300
git push github release-7300
git checkout -b local-audio release-7300
git push github local-audio
```

- `main` は upstream ミラー用として温存（定期 fetch → push のみ、直接コミットしない）。
- revision 更新は「次の branch-heads へ rebase/merge」を四半期程度の周期で計画的に行う。

### 修正 4: API 表記の現代化（原案のコード例を現行 API に合わせる）

現行ヘッダ確認結果に基づく修正:

- **生成方法**: `AudioProcessingBuilder` ではなく（`api/audio/audio_processing.h:95` のコメント例のとおり）

```cpp
scoped_refptr<AudioProcessing> apm =
    BuiltinAudioProcessingBuilder(config).Build(CreateEnvironment());
```

- **AEC3**: `config.echo_canceller.enabled = true; config.echo_canceller.mobile_mode = false;` でデフォルトの echo controller として AEC3 が使われる。`EchoCanceller3Config` を調整したい場合のみ `EchoCanceller3Factory` を builder へ注入する。Phase 3 のチューニングで必要になる前提で、最初から factory 注入経路を実装しておく。
- **AGC は AGC2（`gain_controller2`）を使う**。AGC1 はヘッダ内 TODO（webrtc:7494）で削除予定と明記されている。AGC2 は digital-only なので、原案で触れられていない `set_stream_analog_level()` の呼び出しループは**不要**（Android 側にアナログゲイン制御がないため、これは好都合）。
- **delay 管理**: AEC3 は内部に delay estimator を持つため、`set_stream_delay_ms()` は初期収束の補助という位置づけ。原案 §7 の「実測 → 設定値化」方針は維持するが、合格判定は「delay 値スイープで最良値を探す」ではなく「AEC3 内部推定が収束するか」を主、delay 設定を従とする。

### 修正 5: `liblocal_audio_engine.so` は WebRTC ツリー内 GN target としてビルドする

原案 §14 は engine ソースをアプリ側リポジトリに置き、WebRTC を外部依存としてリンクする構成だが、静的ライブラリ抽出方式には既知の罠がある（`use_custom_libcxx` による libc++ ABI 不整合、シンボル可視性、-f フラグ不一致）。フォークを持つ利点を活かし:

- **engine + JNI のソースをフォーク内の新ディレクトリ `local_audio/` に置き、`rtc_shared_library("local_audio_engine")` としてビルドする**（`webrtc.gni:1007` のテンプレートを使用）。
- GN/ninja はツリー内の任意の BUILD.gn の target をパス指定でビルドできるため、**upstream のファイルには 1 行も手を入れない**（`ninja -C out/android_arm64 local_audio:local_audio_engine`）。upstream 追従時の衝突面がゼロになる。
- アプリ側リポジトリには `.so` と自前ヘッダ（`local_audio_processor.h` 相当の Kotlin/JNI 契約）だけが渡る。原案の「WebRTC API surface をアプリへ露出させない」はこの構成で自動的に満たされる。

### 実行環境まとめ（Apple Silicon Mac 上で完結できるか）

結論: **全フェーズをこの arm Mac 単体で完結できる。** 「Mac が arm か x64 か」で詰まるのは native コンパイラの取得（clang が `Linux_x64` 限定、修正1）の一点だけで、Colima+Rosetta で解消済み。それ以外は元々 macOS ネイティブ or 実機依存であり、arm であることは不利にならない。

| 対象 | 実行場所 | Apple Silicon での可否 |
|---|---|---|
| WebRTC/`local_audio` クロスビルド（Phase 0a/0b/1） | Colima x64 VM（Rosetta） | ✅ |
| Phase 1 オフライン WAV テスト | 同上（x64 Linux バイナリ実行） | ✅ 実機不要 |
| Gradle/Kotlin アプリビルド・APK packaging | macOS ネイティブ（Android Studio, arm64） | ✅ Android Studio は Apple Silicon ネイティブ対応済み。コンテナ側で生成した `.so` を `jniLibs/arm64-v8a/` に置くだけ |
| adb 経由デプロイ | macOS ネイティブ | ✅ |
| Phase 2（基本 audio I/O ループバック確認） | Android Emulator（arm64-v8a system image）on macOS | ✅ Hypervisor.framework でネイティブ動作、Google配布の arm64 イメージが使える |
| Phase 3（AEC3 実音響評価・ERLE 測定）以降 | **物理 Android 端末が必須** | ⚠️ ホストCPUアーキとは無関係の制約。エミュレータはスピーカー→マイクの実音響経路を持たないため、AEC評価はホストが何であれ実機必須（原案 §17 どおり） |

注意点は速度のみ: `gclient sync` 初回（~16GB取得）とAPMコンパイルは Rosetta 越しでもネイティブx64より遅い。ビルド対象を `local_audio:local_audio_engine` に絞ってあるため反復コンパイルは許容範囲、という §2 修正1の前提は変わらない。

## 3. アーキテクチャ（原案採用、差分のみ記載)

以下は原案のまま採用する（再掲しない）:

- 音声フォーマット固定（48kHz / mono / int16 / 10ms / 480 samples）、STT・TTS 側でのみリサンプル、AEC 前のダウンサンプル禁止（§3）
- `LocalAudioProcessor` の 5 操作 API と JNI 境界（§4）
- Capture / Render パイプラインと ring buffer（§5, §6）
- Android 標準 AEC の無効化と二重 AEC 禁止（§8）
- STT / TTS の interface 境界（§9, §10）
- Barge-in 要件（§11）
- スレッド構成と audio callback path での禁止事項（§12）
- ログ・デバッグ dump 方針（§18）
- 実装担当 LLM の独断変更禁止リスト（§19）

差分:

- **STT/TTS/LLM スタック確定**（2026-08-14 決定）: 原案では STT の第一候補を whisper.cpp としていたが、以下に確定した。
  - **STT**: **sherpa-onnx**（SenseVoice / Zipformer 系の日本語対応モデル）
  - **TTS**: **sherpa-onnx + Kokoro-82M**（日本語）— `Japanese Text → sherpa-onnx → Kokoro-82M → PCM`
  - **LLM**: **LiteRT-LM + Gemma E2B** を端末上で実行
  - STT と TTS を sherpa-onnx の単一ランタイムに統一することでネイティブ依存が 1 系統減り、JNI 統合も 1 本化される。`SpeechRecognizer` / `SpeechSynthesizer` interface 境界（原案 §9, §10）はそのまま維持し、backend 差し替え可能性は保つ。
- **VAD**: WebRTC VAD（`common_audio/vad/include/webrtc_vad.h`、C API、単体利用可）を初期実装とする。GMM ベースで感度は粗いが依存ゼロ。sherpa-onnx 同梱の Silero VAD への差し替えを認識率評価後に判断（interface は `SpeechRecognizer` の手前に置くので差し替え自由）。
- **リサンプラ**: 自作しない。`PushResampler`（`common_audio/resampler`）をそのまま使う。48k↔16k、TTS レート→48k の両方に適用。
- **Audio I/O**: 原案どおり AudioRecord / AudioTrack（Java）で開始。レイテンシが AEC3 の許容範囲を超えて問題化した場合のみ AAudio へ移行（現段階では実装しない）。

## 4. リポジトリ構成（修正版・2 リポジトリ）

```
github.com/aRaikoFunakami/libwebrtc        # フォーク
├── main                # upstream main ミラー（直接コミット禁止）
├── release-7300        # upstream branch-heads/7300 ミラー
└── local-audio         # 開発ブランチ（release-7300 起点）
    └── local_audio/    # 唯一の追加ディレクトリ
        ├── BUILD.gn                    # rtc_shared_library("local_audio_engine")
        ├── local_audio_processor.h/.cc # APM ラッパ
        ├── audio_frame_buffer.h/.cc    # 10ms framing ring buffer
        ├── local_audio_jni.cc          # JNI bridge
        └── test/                       # Phase 1 オフライン WAV テスト（Linux ホスト実行可）

<アプリ側リポジトリ>（新規作成、名称例: android-local-voice-agent）
├── app/                        # Android アプリ（Kotlin）
│   └── src/main/jniLibs/arm64-v8a/liblocal_audio_engine.so
├── native-api/                 # JNI 契約ヘッダのコピー（.so とバージョン対で管理）
├── stt/ tts/                   # SpeechRecognizer / SpeechSynthesizer 実装（whisper.cpp 等）
├── docker/                     # WebRTC ビルド用 Dockerfile（Ubuntu + depot_tools）
├── scripts/
│   ├── build_webrtc_android.sh # .gclient 生成 → gclient sync → gn gen → ninja
│   └── generate_notices.sh     # tools_webrtc/libs/generate_licenses.py 呼び出し
└── docs/
    ├── webrtc_revision.md      # WEBRTC_COMMIT / NDK / BUILD_ARGS / COMPILER の記録
    └── third_party_licenses.md
```

原案 §14 の `native/audio` `native/jni` はフォーク側 `local_audio/` へ移動（修正 5 の帰結）。`stt/` `tts/` はアプリ側に残る（WebRTC に依存しないため）。

## 5. ビルド手順（確定）

```bash
# Docker (Ubuntu 24.04) 内で:
fetch --nohooks webrtc_android   # または .gclient を手書きしてフォーク URL を指す（§2 修正 2）
gclient sync                     # ~16GB

cd src
gn gen out/android_arm64 --args='
  target_os="android"
  target_cpu="arm64"
  is_debug=false
  rtc_include_tests=false
  rtc_build_examples=false
  rtc_enable_protobuf=false
  android_static_analysis="off"
'
ninja -C out/android_arm64 local_audio:local_audio_engine
# → out/android_arm64/liblocal_audio_engine.so
```

成果物と同時に記録するもの（原案 §13 どおり）: `WEBRTC_COMMIT`（= フォークの local-audio ブランチ commit）、NDK バージョン（gclient sync が取得したものを `third_party/android_toolchain` から読む）、`gn args out/android_arm64 --list --short` の出力、コンパイラバージョン。

評価用に別ディレクトリで `rtc_include_tests=true` ビルドを作り、`audioproc_f`（`modules/audio_processing/BUILD.gn:764`）を Phase 3 の ERLE 机上評価に使う。

## 6. 開発フェーズ（改訂版）

原案の Phase 0–6 の完了条件はすべて維持。変更点のみ:

**Phase 0a — ビルド環境（新設）**
x86_64 Linux（CI: GitHub Actions ubuntu-latest。ローカル: Docker `--platform linux/amd64` 経由）+ `.gclient`（フォーク指し）+ `gclient sync` が通り、`rtc_shared_library` の hello-world `.so`（APM 未リンク）が APK にロードできる。CI キャッシュが機能している。

**Phase 0b — revision 固定 + NOTICE（原案 Phase 0 の残り）**
`release-7300` / `local-audio` ブランチ構成（§2 修正 3）を確立。`generate_licenses.py` で NOTICE が生成でき、生成物がリンク済み dependency 集合と対応していることを確認。

**Phase 1 — APM 単体（強化）**
原案どおり WAV → 10ms framing → `ProcessReverseStream` / `ProcessStream` の native テストを作るが、**まず Linux ホストバイナリとして実行する**（実機・APK 不要、AddressSanitizer/LeakSanitizer をホストで適用してから Android へ持ち込む）。`audioproc_f` の出力と自前ラッパの出力を同一入力で突き合わせ、ラッパの framing バグを早期検出する。完了条件は原案どおり + AGC2 on/off 切り替え。

**Phase 2 — Android Audio I/O**: 原案どおり。
**Phase 3 — AEC 評価**: 原案どおり + `audioproc_f` による机上再現（実機で採取した raw capture / render reference を入力に、EchoCanceller3Config のパラメータを実機再測定なしでスイープできる）。
**Phase 4 — STT 接続**: 原案どおり（whisper.cpp reference。公式 Android example の存在は既知）。
**Phase 5 — TTS 接続 / Phase 6 — Full local conversation**: 原案どおり。

## 7. リスク一覧

| リスク | 影響 | 対策 |
|---|---|---|
| 対象 AOSP 端末の Audio HAL が強制前処理（AEC/NS）を行う | AEC3 評価不能・二重 AEC | Phase 2 で HAL 構成を確認し端末依存仕様として記録（原案 §8）。`AudioRecord` の audio source は `VOICE_RECOGNITION` または `UNPROCESSED` を試し raw に最も近いものを選定 |
| AudioRecord/AudioTrack のレイテンシが大きく AEC3 の delay 許容を超える | ERLE 低下・barge-in 不成立 | AEC3 内部推定の収束を Phase 3 で確認。不成立なら AAudio 移行を発動 |
| whisper.cpp の日本語リアルタイム性能が対象端末で不足 | Phase 4 遅延 | STT は interface 分離済み。モデルサイズ/runtime 評価は原案どおり別トラック |
| checkout 16GB による CI 時間・コスト | 開発速度低下 | Docker イメージに sync 済みツリーを焼く or Actions キャッシュ。ビルドは local_audio target のみで差分は小さい |
| upstream 追従コスト（branch-heads 更新時の API 変化） | 定期メンテ負荷 | 追加は `local_audio/` 1 ディレクトリのみで衝突面ゼロ。更新時は Phase 1 のホストテストが回帰検知として機能 |
| AGC1 削除など APM API の将来変更 | ビルド破壊 | branch-heads 固定で吸収。AGC2 採用で既知の削除予定を先回り |

## 8. 最初の PR（原案 §20 を維持、順序のみ確定）

1. **PR-0**（アプリ repo）: Dockerfile + `build_webrtc_android.sh` + `.gclient` テンプレート。
2. **PR-1**（フォーク `local-audio`）: `local_audio/BUILD.gn` + `LocalAudioProcessor`（APM ラップ、AEC3/NS/AGC2 設定）+ Linux ホスト WAV テスト。
3. **PR-2**（フォーク）: JNI bridge + Android ロード確認。
4. **PR-3**（アプリ repo）: AudioRecord → JNI → APM → debug PCM sink、および test render PCM → `ProcessReverseStream` → AudioTrack（原案 §20 の最小到達点）。

この段階で実機 AEC が成立しなければ STT/TTS 統合へ進まない（原案どおり）。
