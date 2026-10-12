-- =============================================================================
-- JOB.attempt_count — 무한 재큐 방지
--
-- JobSweeper 는 10분 초과 PROCESSING 을 전부 requeue 했다. 시도 횟수를 세지 않으므로
-- 영구히 실패하는 작업이 5분마다 큐를 돌았다. 주석은 "재시도 1회"라고 적혀 있었지만
-- 셀 것이 없었다. 공통규약 23장은 "10분 초과 시 FAILED 전이 + 재시도 1회"를 요구한다.
--
-- ⚠ 증가 지점은 JobService.CLAIM_SQL 한 곳뿐이다.
--   획득 UPDATE 와 같은 문장에서 올려야 FOR UPDATE SKIP LOCKED 의 원자성이
--   시도 횟수에도 적용된다. 별도 UPDATE 로 빼면 두 워커가 같은 값을 읽어
--   증가분이 유실된다. 엔티티도 updatable = false 로 이 사실을 강제한다.
--
-- V1 상단 마이그레이션 규칙 대조 — "컬럼 추가(nullable 또는 default)"는 허용이다.
-- NOT NULL 을 붙일 수 있는 것은 이 컬럼이 신규이기 때문이다(기존 컬럼에 NOT NULL
-- 추가는 여전히 금지). DEFAULT 가 있으므로 이전 태그 재배포에서 attempt_count 를
-- 모르는 INSERT 도 통과한다 — 롤백 조건 1 이 깨지지 않는다.
-- =============================================================================

ALTER TABLE core.job
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE core.job
    ADD CONSTRAINT ck_job_attempt CHECK (attempt_count >= 0);

-- 인덱스는 손대지 않는다. ix_job_queue (job_type, created_at) WHERE status='QUEUED' 가
-- 획득 쿼리를, ix_job_running (started_at) WHERE status='PROCESSING' 이 스위프 쿼리를
-- 그대로 커버한다. attempt_count 는 WHERE 절에 들어가지 않는다 — 상한 판정은
-- 스위퍼가 애플리케이션에서 한다(운영자가 강제 재큐할 여지를 남기려는 선택).
