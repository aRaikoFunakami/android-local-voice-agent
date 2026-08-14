# WebRTC revision 管理

## 固定 revision

| 項目 | 値 |
|---|---|
| fork | https://github.com/aRaikoFunakami/libwebrtc |
| リリースブランチ | `release-7300`（= upstream `refs/branch-heads/7300`） |
| 起点 commit | `31417145edec85b9e80a1023d78bd45a119c2554` |
| 開発ブランチ | `local-audio`（`release-7300` 起点、追加は `local_audio/` のみ） |
| 記録日 | 2026-08-14 |

NDK バージョン / gn args / コンパイラバージョンは Issue #4（gclient checkout 疎通）完了時に追記する。

## ブランチ運用ルール

- `main`: upstream main のミラー。直接コミット禁止。定期 fetch → push のみ。
- `release-7300`: upstream リリースブランチのミラー。直接コミット禁止。
- `local-audio`: 唯一の開発ブランチ。**upstream ファイルの変更禁止**、追加は `local_audio/` ディレクトリのみ。GN/ninja はツリー内任意の BUILD.gn をパス指定でビルドできるため、トップレベル BUILD.gn の変更も不要。
- **WebRTC main HEAD を version 固定せず依存することを禁止する**（開発計画 §19）。

## revision 更新手順

1. upstream の新しい `refs/branch-heads/<N>` を fork へ `release-<N>` として push
2. `local-audio` を `release-<N>` へ rebase（衝突面は `local_audio/` のみのはず）
3. Linux ホストのオフライン WAV テスト（Issue #11）を回帰確認として実行
4. NOTICE を再生成し差分をレビュー（Issue #8 のパイプライン）
5. 本ファイルと `.gclient` の commit 固定を更新

更新周期の目安: 四半期ごと。
