# Linux checks

A Docker image (Ubuntu 26.04, GTK 4, Xvfb, Jolt) for running Jonquil's Linux checks.
It's built from a Mac with Docker, and `.github/workflows/linux.yml` builds the amd64 image in CI too.
`jolt linux:image [--arch arm64|amd64]` builds `jonquil-linux:<arch>`, defaulting to the host's architecture.
`jolt linux:clean` removes both images and `target/linux`.
Nothing here is published, and the checkout is only ever mounted read-only.
