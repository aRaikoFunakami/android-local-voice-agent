# WebRTC Android ビルド環境（x86_64、Apple Silicon 上では Rosetta で実行）
# DEPS の prebuilt clang が Linux_x64 限定のため amd64 固定。
FROM --platform=linux/amd64 ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    git curl ca-certificates python3 python3-setuptools \
    lsb-release sudo file xz-utils zip unzip pkg-config \
    && rm -rf /var/lib/apt/lists/*

RUN git clone --depth 1 https://chromium.googlesource.com/chromium/tools/depot_tools.git /opt/depot_tools
ENV PATH=/opt/depot_tools:$PATH

# gclient sync 中に必要になる追加パッケージは build/install-build-deps.sh で
# checkout 後に導入する（Issue #4）。イメージは depot_tools まで。
WORKDIR /work
