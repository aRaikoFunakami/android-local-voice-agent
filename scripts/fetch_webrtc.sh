#!/usr/bin/env bash
# webrtc-build コンテナ内で実行する。fork を指す gclient checkout を /work に作る。
# 実行例（ホスト側から）:
#   docker cp scripts/fetch_webrtc.sh webrtc-build:/work/
#   docker exec webrtc-build /work/fetch_webrtc.sh
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

# docs/webrtc_revision.md と一致させること
WEBRTC_COMMIT=31417145edec85b9e80a1023d78bd45a119c2554
FORK_URL=https://github.com/aRaikoFunakami/libwebrtc.git

cd /work

cat > .gclient <<EOF
solutions = [
  {
    "name": "src",
    "url": "${FORK_URL}@${WEBRTC_COMMIT}",
    "deps_file": "DEPS",
    "managed": False,
    "custom_deps": {},
  },
]
target_os = ["android"]
EOF

# 1) ソース取得（hooks なし）。--no-history で容量と時間を節約。
gclient sync --nohooks --no-history

# 2) ビルド依存パッケージ（Android 含む）
./src/build/install-build-deps.sh --android --no-prompt

# 3) hooks（prebuilt clang / NDK / sysroot 等のダウンロード）
gclient runhooks

echo "fetch_webrtc: DONE"
