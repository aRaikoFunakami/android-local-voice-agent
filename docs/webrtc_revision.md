# WebRTC revision 管理

## 固定 revision

| 項目 | 値 |
|---|---|
| fork | https://github.com/aRaikoFunakami/libwebrtc |
| リリースブランチ | `release-7300`（= upstream `refs/branch-heads/7300`） |
| 起点 commit | `31417145edec85b9e80a1023d78bd45a119c2554` |
| 開発ブランチ | `local-audio`（`release-7300` 起点、追加は `local_audio/` のみ） |
| 記録日 | 2026-08-14 |

## ビルド環境実測値（Issue #4、2026-08-14）

| 項目 | 値 |
|---|---|
| ホスト | x86_64 Linux コンテナ（Colima vz+Rosetta on Apple Silicon） |
| Android toolchain (NDK) | CIPD instance `KXOia11cm9lVdUdPlbGLu8sCz6Y4ey_HV2s8_8qeqhgC`（DEPS 固定、`third_party/android_toolchain`） |
| コンパイラ | clang 21.0.0git (`bd809ffb4b5f`, Chromium prebuilt Linux_x64) |
| gn args | `target_os="android" target_cpu="arm64" is_debug=false rtc_include_tests=false rtc_build_examples=false rtc_enable_protobuf=false android_static_analysis="off"` |
| 検証 | `ninja -C out/android_arm64 modules/audio_processing` 成功（1243 targets、`libaudio_processing.a` 生成） |
| checkout サイズ | 約 17GB（`--no-history`） |

## ブランチ運用ルール

- `main`: upstream main のミラー。直接コミット禁止。定期 fetch → push のみ。
- `release-7300`: upstream リリースブランチのミラー。直接コミット禁止。
- `local-audio`: 唯一の開発ブランチ。追加は `local_audio/` ディレクトリ + **ルート BUILD.gn の is_android ガード 4 行のみ**（GN はルートから参照されない BUILD.gn をビルドグラフに載せないため、当初方針の「トップレベル BUILD.gn 変更不要」は誤りと実測で判明。fork PR #1 参照）。それ以外の upstream ファイル変更は禁止。
- **WebRTC main HEAD を version 固定せず依存することを禁止する**（開発計画 §19）。

## revision 更新手順

1. upstream の新しい `refs/branch-heads/<N>` を fork へ `release-<N>` として push
2. `local-audio` を `release-<N>` へ rebase（衝突面は `local_audio/` のみのはず）
3. Linux ホストのオフライン WAV テスト（Issue #11）を回帰確認として実行
4. NOTICE を再生成し差分をレビュー（Issue #8 のパイプライン）
5. 本ファイルと `.gclient` の commit 固定を更新

更新周期の目安: 四半期ごと。
