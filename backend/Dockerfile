# syntax=docker/dockerfile:1
#
# 빌드 컨텍스트는 backend/ 다. 저장소 루트가 아니다.
#   docker build -t jango-api:<빌드번호>-<sha7> backend/
#
# HEALTHCHECK 는 여기 두지 않는다 — compose 에 정의돼 있고(/actuator/health/readiness),
# 두 곳에서 관리하면 compose 쪽 값이 이겨 혼란만 생긴다.

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src
COPY gradle gradle
COPY gradlew settings.gradle build.gradle ./
COPY src src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre-alpine
# tzdata 는 필수다. alpine 은 이게 없으면 TZ 를 무시하고 UTC 로 돈다.
# 금융망이 요청시각 ±5분을 검증하므로(H1003) 시간대가 어긋나면 호출이 전부 거부된다.
#
# uid/gid 1000 = 서버의 배포 계정. ~/app/uploads 가 ubuntu:ubuntu 라
# 팀원이 업로드 디렉터리를 직접 확인·scp 할 수 있다.
RUN apk add --no-cache tzdata \
 && addgroup -g 1000 -S app && adduser -S -G app -u 1000 app \
 && mkdir -p /data/uploads && chown -R app:app /data/uploads
WORKDIR /app
# bootJar 산출물은 하나뿐이다(-plain.jar 없음). 둘이면 이 COPY 가 실패한다.
COPY --from=build /src/build/libs/*.jar app.jar
USER app
# JAVA_TOOL_OPTIONS 를 쓰면 부팅 로그에 "Picked up JAVA_TOOL_OPTIONS" 한 줄이 찍힌다. 정상이다.
# mem_limit 2g × 70% = 힙 약 1.4GB.
ENV TZ=Asia/Seoul \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70"
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
