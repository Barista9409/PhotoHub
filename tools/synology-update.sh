#!/bin/bash
# PhotoHub auto-update for Synology (DSM Task Scheduler, user root, every minute).
#
# Lessons built in:
# - Asks GHCR for the digest of :latest (a tiny request) instead of pulling every time,
#   so it can run every minute and a new version is in within about a minute of being published.
# - Does not depend on Container Manager's API (build_stream failed with code 2104 on DSM).
# - Never runs twice at the same time (lock), and never gets stuck: a stale lock is cleared.
# - Checks the new version actually answers /api/health; if not, goes back to the previous
#   version and does not try that broken version again.
# - Writes the result in plain words to update-last.txt (.txt so File Station opens it).
export PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH
IMG=ghcr.io/barista9409/photohub
DIR=/volume1/docker/photohub
LOG=$DIR/update.txt
LAST=$DIR/update-last.txt
BAD=$DIR/update-bad.txt
LOCK=/tmp/photohub-update.lock
cd "$DIR" || exit 1

now() { date '+%Y-%m-%d %H:%M'; }
say() { echo "$(now) $1" > "$LAST"; }
log() { echo "$(now) $1" >> "$LOG"; }

# one at a time; a lock older than 30 minutes is left over from a crash
if ! mkdir "$LOCK" 2>/dev/null; then
  [ -n "$(find "$LOCK" -maxdepth 0 -mmin +30 2>/dev/null)" ] && rm -rf "$LOCK" && mkdir "$LOCK" 2>/dev/null || exit 0
fi
trap 'rm -rf "$LOCK"' EXIT

# 1) what GHCR has now (digest only, no download)
tok=$(curl -s --max-time 20 "https://ghcr.io/token?scope=repository:${IMG#ghcr.io/}:pull" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
remote=$(curl -sI --max-time 20 -H "Authorization: Bearer $tok" \
  -H "Accept: application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.docker.distribution.manifest.v2+json, application/vnd.oci.image.manifest.v1+json" \
  "https://ghcr.io/v2/${IMG#ghcr.io/}/manifests/latest" | tr -d '\r' | sed -n 's/^[Dd]ocker-[Cc]ontent-[Dd]igest: *//p')
if [ -z "$remote" ]; then
  say "確認できませんでした（インターネットかGitHubにつながりません。次の回にもう一度確かめます）"
  exit 0
fi

# 2) what is running (stopped on purpose, e.g. to edit the YAML: leave it alone)
if [ "$(docker inspect -f '{{.State.Running}}' photohub 2>/dev/null)" != "true" ]; then
  say "PhotoHubが止まっているので、更新は休んでいます（起動すると、また確かめます）"
  exit 0
fi
cur_img=$(docker inspect -f '{{.Image}}' photohub 2>/dev/null)
cur_digests=$(docker image inspect -f '{{range .RepoDigests}}{{println .}}{{end}}' "$cur_img" 2>/dev/null)
if echo "$cur_digests" | grep -q "$remote"; then
  say "確認：最新です（入れ替えなし）"
  exit 0
fi
if grep -qx "$remote" "$BAD" 2>/dev/null; then
  say "新しい版は起動しなかったため、前の版のままです（次に新しい版が出たら入れます）"
  exit 0
fi

# 3) download and swap
log "新しい版を見つけました: $remote"
if ! docker pull -q "$IMG:latest" >> "$LOG" 2>&1; then
  say "ダウンロードに失敗しました（次の回にもう一度やります）"; log "pull failed"; exit 0
fi
[ -n "$cur_img" ] && docker tag "$cur_img" photohub-previous:latest 2>/dev/null
compose() { docker compose up -d "$@" >> "$LOG" 2>&1 || docker-compose up -d "$@" >> "$LOG" 2>&1; }
docker rm -f photohub >> "$LOG" 2>&1
compose

# 4) does it answer?
ok=""
for i in $(seq 1 36); do   # up to 3 minutes
  sleep 5
  curl -s --max-time 4 http://127.0.0.1:8765/api/health | grep -q '"ok": *true' && ok=1 && break
done
if [ -n "$ok" ]; then
  build=$(curl -s --max-time 4 http://127.0.0.1:8765/api/health | sed -n 's/.*"build": *"\([^"]*\)".*/\1/p')
  say "新しい版を入れました（$build）"; log "ok $build"
  docker image prune -f >> "$LOG" 2>&1
else
  log "新しい版が起動しません。前の版に戻します"
  echo "$remote" >> "$BAD"; tail -n 20 "$BAD" > "$BAD.tmp" && mv "$BAD.tmp" "$BAD"
  if docker image inspect photohub-previous:latest >/dev/null 2>&1; then
    docker tag photohub-previous:latest "$IMG:latest"
    docker rm -f photohub >> "$LOG" 2>&1
    compose
  fi
  say "新しい版が起動しなかったので、前の版に戻しました（update.txt を見てください）"
fi

# keep the record short
tail -n 400 "$LOG" > "$LOG.tmp" 2>/dev/null && mv "$LOG.tmp" "$LOG"
