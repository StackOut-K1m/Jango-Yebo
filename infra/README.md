# Jango-Yebo 인프라

서버(`deploy@portfolio.example.invalid`) `~/app` 의 구성 파일 정본. **서버에서 직접 고치지 말고 이 레포를 고친 뒤 반영한다.**

## 구성

```
portfolio.example.invalid (Ubuntu 24.04, 4vCPU / 15GB / 309GB)
│
├─ ufw            22, 80, 443 만 개방 (그 외는 drop)
├─ edge-nginx     80 → 443 리다이렉트, TLS 종단. / · /api/ · /jenkins 프록시  [network: edge]
├─ jango-frontend :80 (컨테이너 내부만), 웹 SPA 정적 서빙. edge 가 `/` 로 프록시  [edge]
├─ jango-jenkins  127.0.0.1:8080, DooD(호스트 docker.sock), executors=2  [edge]
├─ jango-postgres 127.0.0.1:5432, 스키마 core / forecast          [internal]
├─ jango-redis    127.0.0.1:6379, AOF + 512MB + volatile-lru       [internal]
└─ jango-ai       :8000 (컨테이너 내부만), 무상태 AI 워커. nginx 미노출  [internal]
```

- 네트워크 `edge` / `internal` 로 분리 — nginx 는 DB 에 직접 접근할 수 없다.
- 외부 노출은 **80/443 뿐**. Jenkins·DB·Redis 는 `127.0.0.1` 바인딩.
- 컨테이너 시각은 전부 `TZ=Asia/Seoul` (금융망 API 가 요청시각 ±5분을 검증하므로 필수).

## 파일

| 경로 | 서버 위치 | 비고 |
|---|---|---|
| `compose.yml` | `~/app/compose.yml` | 전체 스택 |
| `nginx/conf.d/default.conf` | `~/app/nginx/conf.d/` | server 블록 (80/443) |
| `nginx/locations/*.conf` | `~/app/nginx/locations/` | 443 서버 블록 안에 include. **서비스 추가 시 파일만 떨군다** |
| `nginx/locations/frontend.conf` | `~/app/nginx/locations/` | `location /` 를 **여기가 가진다.** default.conf 에 다시 두면 duplicate location |
| `jenkins/Dockerfile` | `~/app/jenkins/` | Jenkins + docker CLI. `groupadd -g 988` 은 **호스트 docker 그룹 GID** |
| `db/init/01-schemas.sql` | `~/app/db/init/` | **최초 기동 1회만** 실행됨 |
| `backup-db.sh` | `~/app/backup-db.sh` | cron `33 4 * * *` |
| `docker/daemon.json` | `/etc/docker/daemon.json` | 로그 로테이션 10MB × 3 |
| `secrets/*.example` | `~/app/secrets/` | **실값은 서버에만.** 커밋 금지 |
| `uploads/` | `~/app/uploads/` | 증빙·매출 인쇄물 원본. API 서버가 쓰고 `jango-ai` 가 `:ro` 로 읽는다. 레포에 올리지 않음 |
| (`../ai/`) | 이미지 `jango-ai:N` | 워커 소스는 레포 `ai/`. `secrets/ai.env.example` 참고 |
| (`../frontend/`) | 이미지 `jango-frontend:N` | 웹 소스는 레포 `frontend/`. **빌드 컨텍스트는 저장소 루트** (아래) |

## 운영 시 주의

- **env 변경은 restart 로 반영되지 않는다** → `docker compose up -d --force-recreate <svc>`
- `docker compose up` 은 **서비스명을 지정**한다. 전체 실행은 무관한 컨테이너까지 재생성한다.
- nginx 설정 변경 후 **`nginx -t` → `nginx -s reload`** 순서. reload 는 무중단.
- 컨테이너에 마운트하는 파일은 **컨테이너 안 uid 가 읽을 수 있는지** 확인한다 (postgres 999, redis 999, jenkins 1000).
- **`~/app/uploads` 에는 default ACL 이 걸려 있다.** AI 워커(`jango-ai`)가 uid 10001 로 돌면서 이 볼륨을 `:ro` 로 읽기 때문이다. 자세한 것은 아래 「업로드 볼륨 ACL」 참조.
- 이미지 태그에 `:latest` 를 쓰지 않는다 — 롤백 대상이 없어진다.
- **`jango-frontend` 빌드 컨텍스트는 저장소 루트다.** `backend/` · `ai/` 와 규칙이 다르다 —
  `@jangoyebo/core` 가 workspace 패키지이고 `main` 이 `src/index.ts`(원본 TS)라
  `packages/core/src` 와 루트 lockfile 이 컨텍스트에 있어야 한다.
  `docker build -f frontend/Dockerfile -t jango-frontend:<태그> .`
- **`packages/` 가 바뀌면 프론트도 재빌드해야 한다** — 코어가 번들에 통째로 들어간다.
  Jenkins 변경 감지가 이미 그렇게 돼 있다.

## 업로드 볼륨 ACL

`~/app/uploads` 는 `jango-api`(uid 1000)가 쓰고 `jango-ai`(uid **10001**)가 `:ro` 로 읽는다.
앱은 퍼미션을 지정하지 않으므로(`EvidenceStorage`) 파일 모드는 컨테이너 umask 에 달려 있는데,
그 값에 기대면 base 이미지나 entrypoint 가 바뀌는 순간 조용히 깨진다.

→ **default ACL 로 uid 10001 의 읽기를 못박아 두었다.** 파일 모드와 무관하게 동작한다.

```
setfacl -R -m u:10001:rX -m d:u:10001:rX ~/app/uploads
```

- `rX` 의 **대문자 X** — 디렉터리에만 진입(`x`) 권한을 주고 파일에는 주지 않는다
- `d:` 접두어가 **default ACL** 이라 앞으로 생기는 파일·디렉터리에 자동 상속된다
- 실측(2026-09-10): 앱이 만든 파일 `user:10001:r--`, 디렉터리 `user:10001:r-x`, 워커 읽기 성공

확인:

```
getfacl -d ~/app/uploads          # default:user:10001:r-x 가 보여야 한다
```

⚠ **볼륨 경로를 옮기거나 디렉터리를 새로 만들면 ACL 이 따라가지 않는다.** 다시 걸어야 한다.
`STORAGE_ROOT` 를 바꾸는 경우도 마찬가지다.

⚠ **앱에서 `PosixFilePermission` 을 설정하면 안 된다.** ACL 이 준 접근을 덮어써 워커가 못 읽는다.
이 문제는 로컬(같은 사용자)에서도, stub(파일 미열람)에서도 재현되지 않고
**배포 live 첫 실행에서만** 드러난다 — 그때 앱에 퍼미션 코드를 넣는 것이 자연스러워 보이지만
원인은 앱이 아니라 볼륨 쪽이다. 합의 ⑧-2.

## 백업

`pg_dump | gzip` → `~/app/backups/`, 7일 보관. **같은 디스크에 저장되므로 인스턴스 유실에는 무방비.**
중요한 시점(발표 전날, 마이그레이션 직전, 데모 데이터 투입 후)에 로컬로 내려둔다.

```
scp -i <키>.pem deploy@portfolio.example.invalid:~/app/backups/jango_*.sql.gz .
```

## 새 서버에 재현하는 순서

1. ufw: 22, 80, 443 개방
2. Docker 공식 저장소로 설치 → `usermod -aG docker ubuntu` → **재접속**
3. `/etc/docker/daemon.json` 배치 → `systemctl restart docker`
4. `~/app` 디렉터리 생성 (`secrets` 는 700)
5. `secrets/*.example` 을 참고해 실제 시크릿 생성
6. 이 레포의 파일을 `~/app` 에 배치
7. `jenkins/Dockerfile` 의 GID 를 `getent group docker` 결과로 맞춤
8. certbot 으로 인증서 발급 (webroot: `~/app/nginx/certbot/www`)
9. `docker compose up -d nginx postgres redis` → `build jenkins` → `up -d jenkins`
   → (Jenkins 가 `jango-ai:N` 이미지를 빌드한 뒤) `secrets/ai.env` 확인 → `up -d ai-worker`
   → (`jango-frontend:N` 이미지를 빌드한 뒤) `up -d frontend`
10. cron 등록 (백업)

> `frontend` 는 `location /` 를 가지므로, 컨테이너가 뜨기 **전에** nginx 를 reload 하면
> `/` 가 502 가 된다. 순서는 **이미지 → `up -d frontend` → 내부 확인 → `nginx -s reload`** 다.

## 알려진 제약

- **AWS 콘솔 접근 없음** — 보안그룹·EIP·EBS 스냅샷 확인/변경 불가. 포트 개방 여부는 외부에서 반응으로 추론해야 한다(refused = 도달, timeout = 차단).
- **팀원 전원이 동일한 pem 키를 공유** → 모두가 사실상 root. 시크릿 파일 권한은 외부 침입 방어용이며 팀 내부에서는 의미가 없다.
- TLS 인증서 만료 **2026-11-29**. 자동 갱신 미설정(인스턴스 회수 예정). 연장 시 갱신 cron 필요.
- 백업이 off-host 가 아니다. IAM 인스턴스 역할을 붙일 수 없어 S3 전송에는 정적 키가 필요하다.
