#!/bin/sh
#
# 레포 미러의 infra/ 와 서버 ~/app 이 어긋났는지 확인한다. 아무것도 바꾸지 않는다.
#
#   실행:  ~/app/verify-sync.sh
#   종료코드: 0 = 일치, 1 = 어긋남
#
# 네트워크를 쓰지 않는다. 미러가 최신인지 보장하지 않으므로 아래 커밋 해시를 확인할 것.
# 최신 상태로 비교하려면 먼저 sync-infra.sh 의 1단계(git fetch)를 돌린다.
#
# 양방향으로 검사한다
#   - 레포에 있으나 서버와 내용이 다른 파일
#   - 서버에만 있고 레포에 없는 파일  <- 2026-09-07 에 실제로 발생한 유형(api.conf)

MIRROR=/home/ubuntu/src/jango
APP=/opt/jango-yebo
DRIFT=0

echo "미러 커밋: $(cd "$MIRROR" && git rev-parse --short HEAD) (origin/develop: $(cd "$MIRROR" && git rev-parse --short origin/develop 2>/dev/null || echo unknown))"
echo

check_file() {
  repo="$MIRROR/$1"
  srv="$2"
  if [ ! -f "$repo" ] && [ ! -f "$srv" ]; then
    return
  fi
  if [ ! -f "$repo" ]; then
    echo "[레포에 없음] $srv"
    DRIFT=1
    return
  fi
  if [ ! -f "$srv" ]; then
    echo "[서버에 없음] $1"
    DRIFT=1
    return
  fi
  if ! diff -q "$repo" "$srv" > /dev/null 2>&1; then
    echo "[내용 다름] $1"
    diff "$repo" "$srv" | sed 's/^/    /'
    DRIFT=1
  fi
}

# 디렉터리 안에 서버에만 있는 파일을 찾는다
check_extra() {
  repo_dir="$MIRROR/$1"
  srv_dir="$2"
  [ -d "$srv_dir" ] || return
  for f in "$srv_dir"/*; do
    [ -f "$f" ] || continue
    base=$(basename "$f")
    if [ ! -f "$repo_dir/$base" ]; then
      echo "[서버에만 있음] $srv_dir/$base"
      DRIFT=1
    fi
  done
}

check_file infra/compose.yml            "$APP/compose.yml"
check_file infra/jenkins/Dockerfile     "$APP/jenkins/Dockerfile"
check_file infra/backup-db.sh           "$APP/backup-db.sh"
check_file infra/sync-infra.sh          "$APP/sync-infra.sh"
check_file infra/verify-sync.sh         "$APP/verify-sync.sh"
check_file infra/docker/daemon.json     /etc/docker/daemon.json

for f in "$MIRROR"/infra/nginx/conf.d/*.conf; do
  [ -f "$f" ] || continue
  check_file "infra/nginx/conf.d/$(basename "$f")" "$APP/nginx/conf.d/$(basename "$f")"
done
for f in "$MIRROR"/infra/nginx/locations/*.conf; do
  [ -f "$f" ] || continue
  check_file "infra/nginx/locations/$(basename "$f")" "$APP/nginx/locations/$(basename "$f")"
done
for f in "$MIRROR"/infra/db/init/*.sql; do
  [ -f "$f" ] || continue
  check_file "infra/db/init/$(basename "$f")" "$APP/db/init/$(basename "$f")"
done

check_extra infra/nginx/conf.d    "$APP/nginx/conf.d"
check_extra infra/nginx/locations "$APP/nginx/locations"
check_extra infra/db/init         "$APP/db/init"

echo
if [ "$DRIFT" -eq 0 ]; then
  echo "일치. 레포만으로 서버 구성을 재현할 수 있다."
  exit 0
else
  echo "어긋남 발견. 어느 쪽이 정본인지 판단해서 처리한다."
  echo "  레포가 맞다  -> ~/app/sync-infra.sh"
  echo "  서버가 맞다  -> 해당 파일을 레포에 반영하고 커밋 (서버에서 직접 고친 흔적이다)"
  exit 1
fi
