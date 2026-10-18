# A Linux box for checking Jonquil locally (no CI): `bb linux:image` builds it.
#
#   arm64  Jolt publishes no Linux arm64 binary, so it is built from its release
#          tag here (it provisions its own pinned Chez Scheme).
#   amd64  Jolt comes from its pinned release tarball.
FROM ubuntu:26.04

ARG TARGETARCH

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      libgtk-4-1 libpango-1.0-0 adwaita-icon-theme fonts-dejavu-core \
      shared-mime-info desktop-file-utils \
      xvfb xauth x11-utils \
      x11vnc openbox novnc websockify pcmanfm xdg-utils \
      git procps ca-certificates curl libarchive-tools \
      build-essential uuid-dev libncurses-dev liblz4-dev xxd \
 && rm -rf /var/lib/apt/lists/*

ARG JOLT_VERSION=v0.8.23
ARG JOLT_SHA256_AMD64=90031be43241e61d0788a2f1bbd3da56d8f4324ae9fa87271f9637b43a6071f2
ARG BB_VERSION=1.13.225
ARG BB_SHA256_AMD64=ab0e98307405ba19e7c5ba0c3c7cd17a6f90aa90577a72709055f120796d6bfe
ARG BB_SHA256_ARM64=7fd4ce2c6ac9975bfd6f20a42a49f5d6f95d8ac0b2c4d9336dc6afc67ad11ce2

# Downloads use curl --retry 5 --retry-all-errors because GitHub drops the odd
# connection from inside a build. The Jolt archive is unpacked with bsdtar, not
# GNU tar, because GNU tar fails under amd64 emulation on Apple silicon.
RUN set -eu; cd /tmp; get() { curl -fsSL --retry 5 --retry-all-errors -o "$1" "$2"; }; \
    case "$TARGETARCH" in \
      amd64) \
        get jolt.tgz https://github.com/jolt-lang/jolt/releases/download/${JOLT_VERSION}/jolt-${JOLT_VERSION}-x86_64-linux.tar.gz; \
        echo "${JOLT_SHA256_AMD64}  jolt.tgz" | sha256sum -c -; \
        bsdtar xzf jolt.tgz; install -m 755 jolt-${JOLT_VERSION}-x86_64-linux/jolt /usr/local/bin/jolt; \
        get bb.tgz https://github.com/babashka/babashka/releases/download/v${BB_VERSION}/babashka-${BB_VERSION}-linux-amd64-static.tar.gz; \
        echo "${BB_SHA256_AMD64}  bb.tgz" | sha256sum -c - ;; \
      arm64) \
        git -c advice.detachedHead=false clone -q --depth 1 --branch ${JOLT_VERSION} \
            --recurse-submodules --shallow-submodules https://github.com/jolt-lang/jolt.git jolt-src; \
        make -C jolt-src build > /tmp/jolt-build.log 2>&1 || { tail -30 /tmp/jolt-build.log; exit 1; }; \
        install -m 755 jolt-src/target/release/jolt /usr/local/bin/jolt; \
        get bb.tgz https://github.com/babashka/babashka/releases/download/v${BB_VERSION}/babashka-${BB_VERSION}-linux-aarch64-static.tar.gz; \
        echo "${BB_SHA256_ARM64}  bb.tgz" | sha256sum -c - ;; \
      *) echo "unsupported architecture: $TARGETARCH" >&2; exit 1 ;; \
    esac; \
    bsdtar xzf bb.tgz; install -m 755 bb /usr/local/bin/bb; \
    cd /; rm -rf /tmp/*

# jonquil.nrepl.child looks for this checkout at ~/dev/jolt-lang/nrepl, which
# for user dev is the path below, so no environment variable is needed.
ARG NREPL_SHA=b8de37a33d039fd64a98b2563c42bc9918d27104
RUN useradd -m dev \
 && mkdir -p /home/dev/dev/jolt-lang \
 && git clone -q https://github.com/jolt-lang/nrepl.git /home/dev/dev/jolt-lang/nrepl \
 && git -C /home/dev/dev/jolt-lang/nrepl -c advice.detachedHead=false checkout -q ${NREPL_SHA} \
 && chown -R dev:dev /home/dev/dev

USER dev
WORKDIR /home/dev

RUN jolt --version && bb --version
