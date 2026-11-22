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

# Offline place names (GeoNames, CC BY 4.0): built once into the image, so locations never leave the NAS
COPY tools/build_places.py /tmp/build_places.py
RUN mkdir -p /opt/geo /tmp/gn && cd /tmp/gn \
 && curl -fsSL -o cities1000.zip https://download.geonames.org/export/dump/cities1000.zip \
 && curl -fsSL -o admin1CodesASCII.txt https://download.geonames.org/export/dump/admin1CodesASCII.txt \
 && curl -fsSL -o countryInfo.txt https://download.geonames.org/export/dump/countryInfo.txt \
 && python3 -c "import zipfile; zipfile.ZipFile('cities1000.zip').extractall('.')" \
 && python3 /tmp/build_places.py /tmp/gn /opt/geo/places.tsv \
 && rm -rf /tmp/gn /tmp/build_places.py

WORKDIR /app
# Libraries first (changes rarely), PhotoHub's own code last (changes often): an update then only
# downloads the small top layers instead of the whole ~1 GB image.
COPY pyproject.toml ./
RUN python3 -m venv /opt/venv \
 && /opt/venv/bin/python -c "import tomllib; print('\n'.join(tomllib.load(open('pyproject.toml','rb'))['project']['dependencies']))" > /tmp/req.txt \
 && /opt/venv/bin/pip install --no-cache-dir -r /tmp/req.txt
COPY photohub ./photohub
RUN /opt/venv/bin/pip install --no-cache-dir --no-deps .
ENV PATH=/opt/venv/bin:$PATH TZ=UTC PYTHONUNBUFFERED=1 HOME=/tmp
# which build this is (shown in the app: アプリ → Synology側の版), set by GitHub Actions
ARG PHOTOHUB_BUILD=dev
ENV PHOTOHUB_BUILD=$PHOTOHUB_BUILD

EXPOSE 8765
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["photohub", "serve"]

HEALTHCHECK --interval=5m --timeout=10s CMD curl -fsS http://127.0.0.1:8765/api/health || exit 1
