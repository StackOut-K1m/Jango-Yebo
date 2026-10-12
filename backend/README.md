# jango-api 로컬 개발 환경

Spring Boot 4.1.1 / Java 21 / PostgreSQL 16 / Redis 7

## 준비물

- JDK 21
- Docker Desktop (**실행 중이어야 한다** — 테스트가 Testcontainers를 쓴다.
  꺼져 있으면 `./gradlew test -PexcludeDocker`로 336개는 돌릴 수 있다)

## 시작하기

경로는 **저장소 루트 기준**이다.

```bash
# 1. DB · Redis 기동
docker compose -f backend/compose.yml up -d

# 2. 환경변수 준비
cp backend/.env.example backend/.env
```

`.env`에서 채울 값은 **`FINOPENAPI_API_KEY` 하나**다. 서버 `~/app/secrets/finopenapi.env`에서 가져오거나 팀장·백엔드 담당에게 받는다. **환경변수 자체가 없으면 앱이 뜨지 않는다**(2026-09-08 금융망 어댑터부터). 자리표시자인 채로는 뜨지만 첫 금융망 호출에서 실패한다.

```bash
# 3. 실행
cd backend
./gradlew bootRun
```

**`.env`를 셸에 내보낼 필요가 없다.** `application.yml`의 `spring.config.import`가 앱 부팅 때 `./.env`를 직접 읽는다. IDE·터미널 어느 쪽으로 띄워도 같고, **IntelliJ 런 구성에 환경변수를 꽂지 않아도 된다.**

단 **작업 디렉터리가 `backend/`여야 한다.** 경로가 프로세스 작업 디렉터리 기준이라 저장소 루트에서 띄우면 `.env`를 못 찾는다. IntelliJ 기본값은 모듈 디렉터리라 대개 맞지만, 안 뜨면 Run Configuration → Working directory를 먼저 본다.

안 잡혔을 때의 증상은 **원인을 전혀 가리키지 않는다.** 「설정 누락」이 아니라 「DB 드라이버 문제」로 보인다 — `@ConfigurationProperties` 바인더가 못 찾은 플레이스홀더를 오류로 내지 않고 원문 그대로 넘기기 때문이다.

```
Driver org.postgresql.Driver claims to not accept jdbcUrl,
jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}
```

`${...}`가 그대로 보이면 값이 비거나 틀린 게 아니라 **`.env`를 아예 못 읽은 것**이다.

`.env`에 `export` 접두사·따옴표·백슬래시를 쓰지 않는다. `.properties`로 파싱하므로 값을 따옴표로 감싸면 **따옴표까지 값이 된다.**

### STS · Eclipse로 띄운다면 — 체크박스 하나를 먼저 켠다

```
Window → Preferences → Java → Compiler
  ☑ Store information about method parameters (usable via reflection)
```

**안 켜면 경로 변수를 쓰는 API가 전부 500이 된다.** 증상이 원인을 전혀 가리키지 않으니 미리 켜 두는 편이 낫다.

```
java.lang.IllegalArgumentException: Name for argument of type [java.lang.Long] not specified,
and parameter name information not available via reflection.
Ensure that the compiler uses the '-parameters' flag.
```

Gradle로 띄우면(`./gradlew bootRun`) Spring Boot 플러그인이 `-parameters`를 자동으로 붙여 문제가 없다. STS는 Gradle이 아니라 자체 컴파일러(JDT)로 빌드하고, 그쪽 기본값이 꺼져 있어서 `@PathVariable Long patternId`의 **이름을 못 읽는다**. 저장소에는 `@PathVariable`·`@RequestParam` 53곳이 이름 없이 쓰여 있어 영향 범위가 넓다.

`.settings/`는 gitignore 대상이라 이 설정은 저장소로 전파되지 않는다. **각자 한 번 켜야 한다.** 프로젝트에 개별 설정이 켜져 있으면 `프로젝트 우클릭 → Properties → Java Compiler`에서도 같은 항목을 켠다.

첫 기동 때 Flyway가 `V1`·`V2`를 적용한다. `http://localhost:8080/actuator/health`가 `UP`이면 정상이다.

## 테스트

```bash
cd backend
./gradlew test                          # 전체. Docker 필요
./gradlew test -PexcludeDocker          # Docker 없이 도는 것만 (336개 · 20초대)
./gradlew test --tests MigrationTests   # 스키마만
```

**`-PexcludeDocker`는 컨테이너가 필요한 테스트를 뺀다.** Docker Desktop이 죽어 있을 때,
그리고 CI에서 쓴다 — Jenkins는 DooD라 워크스페이스 경로가 호스트에 없어 컨테이너가 뜨지 않는다.

**컨테이너가 필요한 테스트는 `@NeedsDocker`를 단다.** 그 애너테이션이 `TestcontainersConfiguration`을
들여오는 통로이자 `@Tag("docker")` 표시라, 컨테이너만 가져가고 표시를 빠뜨릴 방법이 없다. 새 테스트를
쓸 때 `-PexcludeDocker` 쪽을 따로 챙길 필요가 없다는 뜻이다 — 붙이면 빠지고, 안 붙이면 들어간다.

**테스트에는 `.env`가 필요 없다.** `build.gradle`의 `tasks.named('test')`가 환경변수를 주입하고 DB·Redis는 Testcontainers가 띄운다.

`.env`가 있으면 테스트에서도 임포트되지만 **환경변수가 우선순위에서 이긴다**(OS 환경변수 > config import). 그래서 지켜야 하는 불변식이 하나 생긴다 — **`.env.example`에 키를 추가하면 `tasks.named('test')`에도 같은 키를 넣는다.** 안 넣으면 그 값이 각자의 `.env`에서 테스트로 새어 들어가고, 내 기계에서만 되는(또는 안 되는) 테스트가 된다.

## DB 초기화

스키마를 고친 뒤 백지에서 다시 적용하고 싶을 때:

```bash
docker compose -f backend/compose.yml down -v
```

볼륨까지 지우므로 앱을 다시 띄우면 마이그레이션이 처음부터 돈다.

## 알아둘 것

**서버 DB(EC2)에 로컬 앱을 붙이지 않는다.** Flyway가 앱 부팅 시 실행되므로, 한 번이라도 붙으면 그 부팅이 `V1`을 적용하고 체크섬을 고정한다 — 이후 `V1`을 고칠 수 없다. 공유 DB에 각자 앱을 붙이면 스키마가 움직이는 동안 상시로 checksum mismatch가 난다.

**스키마의 유일한 기준은 마이그레이션 파일이다.** `ddl-auto: validate`라 엔티티와 테이블이 어긋나면 부팅이 실패한다. 컬럼을 바꾸면 마이그레이션과 엔티티를 함께 고친다.

**서버에 배포된 뒤에는 적용된 마이그레이션 파일을 수정하지 않는다.** 체크섬이 바뀌면 앱이 뜨지 않는다. 틀린 것을 발견했을 때의 정답은 항상 "다음 번호 파일을 새로 만든다"다. 그때는 컬럼 추가(nullable/default)·인덱스 추가·새 테이블만 허용되고, 삭제·`NOT NULL` 추가·타입 변경은 다음 릴리스로 분리한다.

**`spring-modulith-starter-jpa`를 넣으면 부팅이 실패한다.** `event_publication` 테이블을 요구하는데 아직 도메인 이벤트를 발행하지 않는다. 첫 이벤트 도입 시 스타터와 마이그레이션을 함께 넣는다.

**Boot 4는 패키지가 옮겨진 게 많다.** 기존 지식으로 import 하면 컴파일이 안 된다.

| 예상 | 실제 |
| --- | --- |
| `spring-boot-starter-web` | **`spring-boot-starter-webmvc`** |
| `org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc` | **`org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`** |
| `com.fasterxml.jackson.databind.ObjectMapper` | **Jackson 3** → `tools.jackson.databind.ObjectMapper` (애노테이션만 `com.fasterxml.jackson.annotation` 에 남는다) |

**`@SpringBootTest`는 실제 톰캣을 띄우지 않는다.** `webEnvironment` 기본값이 `MOCK`이라 서블릿 컨테이너 초기화 경로를 타지 않는다 — 필터 등록 문제처럼 **기동 시점에만 드러나는 결함은 테스트가 잡지 못한다.** 보안 필터나 서블릿 등록을 건드렸으면 실제로 띄워서 확인한다.

**앱을 멈출 때 포트를 확인한다.** gradle을 종료해도 포크된 java 프로세스가 8080을 계속 잡고 있을 수 있고, 그러면 다음 기동이 `Port 8080 was already in use`로 죽는다.

```bash
netstat -ano | grep ":8080 .*LISTENING"
```

## 더 볼 것

| 문서 | 내용 |
| --- | --- |
| [`docs/api/99_구현현황.md`](../docs/api/99_구현현황.md) | 지금 호출 가능한 엔드포인트와 명세와 다른 지점 |
| [`docs/ERD.md`](../docs/ERD.md) | 테이블 정의의 기준 |
| [`docs/기능별_기술스택_선정.md`](../docs/기능별_기술스택_선정.md) | 의존성을 언제 무엇을 추가하는가 (§3.2) |
| [`infra/README.md`](../infra/README.md) | 서버 구성 |
