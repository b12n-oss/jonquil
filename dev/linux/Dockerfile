# A Linux box for checking Jonquil: `jolt linux:image` builds it locally, and
# .github/workflows/linux.yml builds the amd64 image in CI.
# Jolt runs the tasks too (it reads bb.edn), so there is no babashka here.
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

ARG JOLT_VERSION=v0.8.27
ARG JOLT_SHA256_AMD64=7aedce645a5840772a74e0341da73e6e072f41107ee2b304fc7a4947021a64b4

# Downloads use curl --retry 5 --retry-all-errors because GitHub drops the odd
# connection from inside a build. The Jolt archive is unpacked with bsdtar, not
# GNU tar, because GNU tar fails under amd64 emulation on Apple silicon.
RUN set -eu; cd /tmp; get() { curl -fsSL --retry 5 --retry-all-errors -o "$1" "$2"; }; \
    case "$TARGETARCH" in \
      amd64) \
        get jolt.tgz https://github.com/jolt-lang/jolt/releases/download/${JOLT_VERSION}/jolt-${JOLT_VERSION}-x86_64-linux.tar.gz; \
        echo "${JOLT_SHA256_AMD64}  jolt.tgz" | sha256sum -c -; \
        bsdtar xzf jolt.tgz; install -m 755 jolt-${JOLT_VERSION}-x86_64-linux/jolt /usr/local/bin/jolt ;; \
      arm64) \
        git -c advice.detachedHead=false clone -q --depth 1 --branch ${JOLT_VERSION} \
            --recurse-submodules --shallow-submodules https://github.com/jolt-lang/jolt.git jolt-src; \
        make -C jolt-src build > /tmp/jolt-build.log 2>&1 || { tail -30 /tmp/jolt-build.log; exit 1; }; \
        install -m 755 jolt-src/target/release/jolt /usr/local/bin/jolt ;; \
      *) echo "unsupported architecture: $TARGETARCH" >&2; exit 1 ;; \
    esac; \
    cd /; rm -rf /tmp/*

# jonquil.nrepl.child looks for this checkout at ~/dev/jolt-lang/nrepl, which
# for user dev is the path below, so no environment variable is needed.
ARG NREPL_SHA=927dc288b2627fbdf21752391250d7231da6044d
RUN useradd -m dev \
 && mkdir -p /home/dev/dev/jolt-lang \
 && git clone -q https://github.com/jolt-lang/nrepl.git /home/dev/dev/jolt-lang/nrepl \
 && git -C /home/dev/dev/jolt-lang/nrepl -c advice.detachedHead=false checkout -q ${NREPL_SHA} \
 && chown -R dev:dev /home/dev/dev

USER dev
WORKDIR /home/dev

RUN jolt --version
