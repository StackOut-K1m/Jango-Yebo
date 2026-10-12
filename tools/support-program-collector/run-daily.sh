#!/usr/bin/env bash
#
# 지원사업 공고 수집 — 매일 1회 (P1-15 · 공통규약 23장)
#
# crontab 에서 부른다. 수집기 자체는 `tools/support-program-collector/collect.mjs` 이고
# 여기서 하는 일은 셋이다 — **겹쳐 도는 것을 막고, 로그를 남기고, 조용한 실패를 시끄럽게
# 만든다.**
#
#   0 3 * * * <저장소>/tools/support-program-collector/run-daily.sh
#
# 03:00 인 이유는 공통규약 23장이 정한 값이고, **예측 일배치(04:00)보다 앞**이어야
# 그날의 맞춤 추천(17.3)이 새 공고를 포함한다.
#
# **`infra/` 를 건드리지 않는다.** 배포 반영(`sync-infra.sh`)과 서버 crontab 등록은
# 인프라 담당 몫이고, 여기 있는 것은 수집기를 감싸는 얇은 껍데기다.
#
# ── 환경 변수 (전부 선택) ────────────────────────────────────────────────────
#   JANGO_REPO         저장소 경로. 기본은 이 스크립트 위치에서 거슬러 올라가 찾는다
#   COLLECTOR_ENV_FILE 설정 파일.  기본 $JANGO_REPO/backend/.env
#                      서버에서는 ~/app/secrets/jango-api.env 를 가리킨다 —
#                      AI_SERVICE_TOKEN 이 이미 거기 있어 시크릿을 두 벌로 두지 않는다
#   COLLECT_LOG        로그 파일.  기본 $JANGO_REPO/../collect-programs.log
#   COLLECT_ARGS       수집기 인자. 예) --region 광주
#
# 시크릿은 여기서 다루지 않는다. 경로만 넘기고 값은 수집기가 직접 읽는다.

set -euo pipefail

# 스크립트가 `tools/support-program-collector/` 안에 있으므로 두 단계 위가 저장소다.
# **서버 경로를 박지 않는다** — 미러 위치는 인프라가 정하고 바뀔 수 있다.
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="${JANGO_REPO:-$(cd "$HERE/../.." && pwd)}"
LOG="${COLLECT_LOG:-$(dirname "$REPO")/collect-programs.log}"
LOCK="/tmp/jango-collect-programs.lock"

log() { printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S%z')" "$*" >>"$LOG"; }

# ── 겹침 방지 ────────────────────────────────────────────────────────────────
# 1,526건을 처음 받는 날은 상세 페이지를 0.7초 간격으로 훑어 18분이 걸릴 수 있다.
# 그 사이 다음 실행이 겹치면 **남의 서버를 두 배로 두드린다**. 중복 적재 자체는
# 안전하지만(uk_sp_source upsert) 예의의 문제이고, 구조화가 두 번 걸리면 돈이 나간다.
#
# ⚠ **`flock` 이 없을 때를 먼저 가른다.** 없는 채로 `flock -n 9` 를 부르면 명령이 실패하고,
# 그 실패가 「이미 잠겨 있다」와 구분되지 않아 **매일 조용히 건너뛰며 0 으로 끝난다** —
# 이 스크립트가 없애려는 바로 그 실패 모양이다(작성 중 실제로 그렇게 짰다가 테스트에서
# 잡혔다). 겹침을 막을 수 없으면 돌리지 않고 시끄럽게 끝낸다.
if ! command -v flock >/dev/null 2>&1; then
  log "실패 — flock 이 없다. 겹침을 막을 수 없어 실행하지 않는다 (util-linux 필요)"
  exit 1
fi

exec 9>"$LOCK"
if ! flock -n 9; then
  log "건너뜀 — 앞선 실행이 아직 돌고 있다 ($LOCK)"
  exit 0
fi

cd "$REPO" || { log "실패 — 저장소를 찾을 수 없다: $REPO"; exit 1; }

log "시작 ${COLLECT_ARGS:+(인자: $COLLECT_ARGS)}"

# shellcheck disable=SC2086
if ! output=$(node tools/support-program-collector/collect.mjs ${COLLECT_ARGS:-} 2>&1); then
  printf '%s\n' "$output" >>"$LOG"
  log "실패 — 수집기가 0 이 아닌 코드로 끝났다"
  exit 1
fi

printf '%s\n' "$output" >>"$LOG"

# ── 조용한 실패를 시끄럽게 만든다 ────────────────────────────────────────────
# **이 수집기의 주된 고장 방식은 예외가 아니라 「0건」이다.** 인증키가 만료되거나
# 기업마당이 응답 형식을 바꾸면 목록이 빈 배열로 와서 `목록 0건` 을 찍고 정상 종료한다
# (2026-09-15 에 실제로 겪었다 — 문서의 필드 이름이 실제 응답과 달라 0건이 나왔다).
#
# 그대로 두면 아무도 모르는 채 공고가 며칠씩 안 들어온다. 여기서 비정상으로 바꾼다.
if printf '%s' "$output" | grep -q '목록 0건'; then
  log "실패 — 목록이 0건이다. 인증키 만료 또는 기업마당 응답 형식 변경을 의심한다"
  exit 1
fi

# 구조화큐가 곧 비용이다(Haiku 기준 공고당 약 $0.0066). 평소보다 크면 출처가 대량
# 수정했거나 본문 파싱이 깨진 것이라 사람이 한 번 봐야 한다. 막지는 않는다 —
# `--max-structure` 상한이 이미 사고를 막고 있고, 여기서 끊으면 적재만 되고
# 구조화가 안 된 공고가 남는다.
queued=$(printf '%s' "$output" | sed -n 's/.*구조화큐 \([0-9]*\).*/\1/p' | tail -1)
if [ -n "$queued" ] && [ "$queued" -gt 300 ]; then
  log "주의 — 구조화큐 ${queued}건. 평소보다 크다(비용이 여기에 비례한다)"
fi

log "완료 ${queued:+(구조화큐 ${queued}건)}"
