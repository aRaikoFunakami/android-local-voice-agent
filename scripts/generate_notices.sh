#!/usr/bin/env bash
# liblocal_audio_engine.so に実際にリンクされた dependency から NOTICE を機械生成する。
# 前提: webrtc-build コンテナで out/android_arm64 が gn gen 済み。
# WebRTC revision 更新時は本スクリプトを再実行し、差分をレビューすること。
set -euo pipefail
cd "$(dirname "$0")/.."

docker exec webrtc-build sh -c '
  cd /work/src &&
  python3 tools_webrtc/libs/generate_licenses.py \
    --target //local_audio:local_audio_engine /work/ out/android_arm64 &&
  cp LICENSE PATENTS /work/
'
docker cp webrtc-build:/work/LICENSE.md docs/webrtc_third_party_licenses.md
docker cp webrtc-build:/work/LICENSE docs/webrtc-LICENSE
docker cp webrtc-build:/work/PATENTS docs/webrtc-PATENTS
echo "generated: docs/webrtc_third_party_licenses.md (linked deps of local_audio_engine)"
grep '^# ' docs/webrtc_third_party_licenses.md | sed 's/^# /  - /'
