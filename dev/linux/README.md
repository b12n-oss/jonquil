# Linux checks

A Docker image (Ubuntu 26.04, GTK 4, Xvfb, Jolt, babashka) for running Jonquil's Linux checks.
It is local-only: built from a Mac with Docker, and there is no CI for it.
`bb linux:image [--arch arm64|amd64]` builds `jonquil-linux:<arch>`, defaulting to the host's architecture.
`bb linux:clean` removes both images and `target/linux`.
Nothing here is published, and the checkout is only ever mounted read-only.
