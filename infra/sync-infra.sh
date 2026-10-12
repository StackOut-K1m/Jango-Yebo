#!/bin/sh
#
# 레포(develop)의 infra/ 를 서버 ~/app 에 반영한다.
#
#   실행:  ~/app/sync-infra.sh
#
# 규칙
#   - ~/src/jango 는 읽기 전용 미러다. 거기서 파일을 편집하지 않는다.
#     항상 origin/develop 으로 덮어쓰므로 로컬 수정은 유실된다.
#   - 서버 구성을 바꿀 일이 생기면 레포를 먼저 고치고 이 스크립트로 반영한다.
#   - secrets/ 는 복사하지 않는다. 레포에는 .example 만 있고 실값은 서버에만 있다.
#   - 이 스크립트는 파일만 반영하고 컨테이너를 건드리지 않는다.
#     재생성이 필요한지는 사람이 판단한다(설정에 따라 reload 로 끝나기도 한다).
#
# git fetch 단계에서 GitLab 자격증명을 묻는다.
# deploy token 의 username / token 을 입력하면 된다.

set -e

MIRROR=/home/ubuntu/src/jango
APP=/opt/jango-yebo

echo "=== 1. 미러 갱신 ($MIRROR) ==="
cd "$MIRROR"
git fetch origin develop
git checkout origin/develop -- infra/
echo "미러 커밋: $(git rev-parse --short origin/develop)"

echo
echo "=== 2. ~/app 에 반영 ==="
cp -v infra/compose.yml            "$APP/compose.yml"
cp -v infra/nginx/conf.d/*.conf    "$APP/nginx/conf.d/"
cp -v infra/nginx/locations/*.conf "$APP/nginx/locations/"
cp -v infra/jenkins/Dockerfile     "$APP/jenkins/Dockerfile"
cp -v infra/db/init/*.sql          "$APP/db/init/"
cp -v infra/backup-db.sh           "$APP/backup-db.sh"
cp -v infra/sync-infra.sh          "$APP/sync-infra.sh"
cp -v infra/verify-sync.sh         "$APP/verify-sync.sh"
chmod +x "$APP/backup-db.sh" "$APP/sync-infra.sh" "$APP/verify-sync.sh"

echo
echo "=== 3. 검증 ==="
cd "$APP"
docker compose config --quiet && echo "compose 문법 OK"
docker exec edge-nginx nginx -t

echo
echo "=== 완료 ==="
echo "파일만 반영했다. 컨테이너 재생성 여부는 변경 내용을 보고 판단한다."
echo "  nginx 설정만 바뀜        -> docker exec edge-nginx nginx -s reload"
echo "  compose 의 서비스 정의 변경 -> docker compose up -d --force-recreate <서비스명>"
echo "  jenkins/Dockerfile 변경  -> docker compose build jenkins && docker compose up -d --force-recreate jenkins"
