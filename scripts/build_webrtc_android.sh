#!/usr/bin/env bash
# liblocal_audio_engine.so をビルドして app/src/main/jniLibs/arm64-v8a/ へ配置する。
# 前提: scripts/build_env.sh 済み（webrtc-build コンテナ稼働中）+ scripts/fetch_webrtc.sh 済み。
# 使い方: scripts/build_webrtc_android.sh [<fork の commit/branch>]
set -euo pipefail
cd "$(dirname "$0")/.."

REF=${1:-local-audio}
OUT=out/android_arm64
DEST=app/src/main/jniLibs/arm64-v8a

# fork の指定 ref へ更新（DEPS が変わった場合は gclient sync を促す）
docker exec webrtc-build sh -c "
  cd /work/src &&
  git reset --hard -q && git clean -fdq -- local_audio &&  # 開発中の docker cp 残骸を除去
  git fetch -q origin refs/heads/local-audio &&
  git checkout -q $REF &&
  if ! git diff --quiet HEAD@{1} HEAD -- DEPS 2>/dev/null; then
    echo 'NOTE: DEPS changed; run scripts/fetch_webrtc.sh (gclient sync) if the build fails'
  fi &&
  gn gen $OUT --args='target_os=\"android\" target_cpu=\"arm64\" is_debug=false rtc_include_tests=false rtc_build_examples=false rtc_enable_protobuf=false android_static_analysis=\"off\"' > /dev/null &&
  ninja -C $OUT local_audio:local_audio_engine
"

mkdir -p "$DEST"
docker cp webrtc-build:/work/src/$OUT/liblocal_audio_engine.so "$DEST/"
docker exec webrtc-build sh -c "cd /work/src && git rev-parse HEAD && sha256sum $OUT/liblocal_audio_engine.so"
echo "placed: $DEST/liblocal_audio_engine.so"
