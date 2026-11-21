# PhotoHub NAS service (DS720+ = x86_64). Ubuntu 24.04 ships Python 3.12.
FROM ubuntu:24.04

ARG GPTH_VERSION=6.4.1
ARG GPTH_SHA256=d458c878fb3295e87a35bc9280c6f09df6c3a6b94935c0e80365a122e54a5bb6
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 python3-venv libimage-exiftool-perl ffmpeg \
      p7zip-full curl ca-certificates tini \
 && rm -rf /var/lib/apt/lists/*

# GPTH Neo, pinned by version and checksum (research §2.1)
RUN curl -fsSL -o /usr/local/bin/gpth \
      "https://github.com/Xentraxx/GooglePhotosTakeoutHelper_Neo/releases/download/v${GPTH_VERSION}/gpth_neo-${GPTH_VERSION}-release-linux-x64.bin" \
 && echo "${GPTH_SHA256}  /usr/local/bin/gpth" | sha256sum -c - \
 && chmod +x /usr/local/bin/gpth

WORKDIR /app
COPY pyproject.toml ./
COPY photohub ./photohub
RUN python3 -m venv /opt/venv && /opt/venv/bin/pip install --no-cache-dir .
ENV PATH=/opt/venv/bin:$PATH TZ=UTC PYTHONUNBUFFERED=1 HOME=/tmp
# which build this is (shown in the app: アプリ → Synology側の版), set by GitHub Actions
ARG PHOTOHUB_BUILD=dev
ENV PHOTOHUB_BUILD=$PHOTOHUB_BUILD

EXPOSE 8765
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["photohub", "serve"]

HEALTHCHECK --interval=5m --timeout=10s CMD curl -fsS http://127.0.0.1:8765/api/health || exit 1
