#!/usr/bin/env bash
# WebRTC ビルド用 x86_64 Linux 環境を用意し、常駐コンテナ webrtc-build を起動する。
# 使い方:
#   scripts/build_env.sh          # 起動（冪等）
#   docker exec -it webrtc-build bash
set -euo pipefail
cd "$(dirname "$0")/.."

PROFILE=x64
CONTAINER=webrtc-build
VOLUME=webrtc-work   # gclient checkout (~16GB) は named volume に置く（bind mount は I/O が遅い）

if ! colima status --profile "$PROFILE" >/dev/null 2>&1; then
  # vz + Rosetta: VM は arm64、amd64 コンテナのバイナリを Rosetta が変換実行する。
  # --arch x86_64 を指定してはいけない（vz は arm64 VM のみ。qemu フォールバックで激遅になる）。
  colima start --profile "$PROFILE" --vm-type vz --vz-rosetta --cpu 6 --memory 16 --disk 120
fi

docker context use "colima-$PROFILE" >/dev/null
docker build --platform linux/amd64 -t webrtc-build docker/webrtc-build
docker volume create "$VOLUME" >/dev/null

if [ "$(docker ps -q -f name="^${CONTAINER}$")" = "" ]; then
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  docker run -d --platform linux/amd64 --name "$CONTAINER" \
    -v "$VOLUME":/work -w /work webrtc-build sleep infinity >/dev/null
fi

echo "ready: docker exec -it $CONTAINER bash"
# 動作確認: Rosetta で x86_64、depot_tools の gclient が起動できること
docker exec "$CONTAINER" sh -c 'uname -m && gclient help >/dev/null && echo "gclient: OK"'
