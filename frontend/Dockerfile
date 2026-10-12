# syntax=docker/dockerfile:1
#
# 빌드 컨텍스트는 저장소 루트다. backend/ · ai/ 와 다르다.
#   docker build -f frontend/Dockerfile -t jango-frontend:<빌드번호>-<sha7> .
#
# 이유: @jangoyebo/core 가 workspace 패키지이고 main 이 src/index.ts(원본 TS)라
#       packages/core/src 와 루트 lockfile 이 컨텍스트에 있어야 한다.

FROM node:22-alpine AS base
WORKDIR /src

# 의존성 레이어를 소스와 분리해 캐시를 살린다.
# mobile/package.json 은 루트 workspaces 해석에 필요해서 복사만 한다 — 설치는 하지 않는다.
COPY package.json package-lock.json ./
COPY packages/core/package.json packages/core/
COPY frontend/package.json frontend/
COPY mobile/package.json mobile/
# 웹 빌드에 필요한 워크스페이스만 설치한다. 모바일(React Native·네이티브 모듈)까지 받으면 느리고,
# 네이티브 설치 스크립트가 네트워크에 매여 CI 가 실패할 수 있다.
# 루트도 넣는다 — tsc -b 가 테스트 파일까지 검사해 루트 devDependency 인 vitest 타입이 필요하다.
RUN npm ci -w frontend -w packages/core --include-workspace-root

COPY packages/ packages/
COPY frontend/ frontend/

# 테스트 타깃. AI 워커와 같은 방식이다 — Jenkins 가 `--target test` 로 따로 친다.
#   docker build --target test -f frontend/Dockerfile -t jango-frontend:test-<태그> .
#
# **여기서 끊으므로 vite 빌드(tsc -b 포함)는 돌지 않는다.** 타입 오류는 Build 단에서
# 드러나는 편이 어느 쪽이 깨졌는지 구분하기 쉽다. 기본 빌드는 이 스테이지를 지나가지
# 않는다 — 마지막 스테이지가 build 만 참조하므로 그래프에 들어오지 않는다.
FROM base AS test
RUN npm test

FROM base AS build
RUN npm run build -w frontend

FROM nginx:1.27-alpine
ENV TZ=Asia/Seoul
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/frontend/dist /usr/share/nginx/html
EXPOSE 80
