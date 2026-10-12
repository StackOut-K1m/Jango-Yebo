# 내부 AI 워커 이미지 (명세 20장). 3단이다.
#
#   base     의존성 + 앱 코드 — 테스트·런타임이 공유한다
#   test     dev 그룹 + 테스트 코드 → pytest. CI 가 `--target test` 로 쓴다
#   runtime  비특권 사용자로 uvicorn. **기본 타깃**
#
# **torch 가 들어간다** (S15P21C105-76). 리눅스에서는 CPU 전용 인덱스를 쓰므로 CUDA
# 스택을 받지 않는다 — pyproject 의 `[tool.uv.sources]` 참고. 서버에 GPU 가 없다.
#
# **가중치(`residual-gru.pt`)는 굽지 않는다.** `ai/artifacts/` 는 커밋 금지라(ai/CLAUDE.md)
# Jenkins 가 clone 해도 빌드 컨텍스트에 없다. `/app/artifacts` 로 볼륨을 붙인다
# (infra/compose.yml). 마운트가 없으면 예측만 「모델 미상주」가 되고 워커는 뜬다.

FROM python:3.12.13-slim AS base
ARG UV_VERSION
RUN test -n "$UV_VERSION" && pip install --no-cache-dir "uv==$UV_VERSION"
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 TZ=Asia/Seoul
# opencv-python-headless 가 libgthread-2.0 을 요구한다 — slim 이미지에는 없다
RUN apt-get update && apt-get install -y --no-install-recommends libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project --extra forecast
COPY app ./app

# ── 테스트 (CI 전용. 런타임 이미지에는 안 들어간다) ─────────────────────────
# 실패하면 **빌드가 실패한다.** 이게 -71 의 CI 게이트다.
#
# ruff 는 일부러 넣지 않았다 — 남의 파일 lint 하나로 배포가 막히면 안 된다
# (ai/CLAUDE.md 「남의 파일은 같이 고치지 말고 담당자에게 넘긴다」).
FROM base AS test
RUN uv sync --frozen --no-install-project --extra forecast
# 컨텍스트 전체를 넣는다. 테스트가 최상위 패키지 셋을 import 하는데(`tests`·
# `experiments`·`evals`) 하나씩 나열하면 파트가 늘 때마다 조용히 깨진다.
# 무거운 것은 .dockerignore 가 이미 뺀다 (.venv · datasets · artifacts).
COPY . .
RUN /app/.venv/bin/pytest -q

# ── 런타임 (기본 타깃) ──────────────────────────────────────────────────────
FROM base AS runtime
# 마운트 지점을 미리 만들어 둔다 — 없으면 docker 가 root 소유로 만든다
RUN useradd --uid 10001 --create-home worker \
    && mkdir -p /app/artifacts && chown worker:worker /app/artifacts
USER worker
EXPOSE 8000
CMD ["/app/.venv/bin/uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
