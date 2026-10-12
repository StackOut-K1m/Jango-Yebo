-- =============================================================================
-- 검토 신호를 정렬·필터 키로 (13.2, P1-02)
--
-- 근거: docs/api/98_AI_백엔드_합의.md ③·⑥ · docs/api/13_증빙_품목.md 13.2
--       docs/api/20_내부_AI워커.md v1.18 (validation.needsReview)
--
-- P1-02 완료 기준이 「필드 강조」로 확정되면서(2026-09-11) 검토 화면의 "먼저 볼 곳"은
-- validation.needsReview 정렬로 만들어진다. 그런데 그 값이 evidence_extraction.header_data
-- JSONB 안에 있어 ORDER BY·WHERE 의 키가 되지 못했다 — 목록은 evidence 를 페이징하므로
-- 조인해 꺼내도 Pageable 의 Sort 와 맞물리지 않는다.
--
-- ⚠ 이것은 상태가 아니다. 합의 ③ 이 "ocrStatus 에 REVIEW 를 만들지 않는다" 고 정했고
--   그 결정은 그대로다 — 모든 추출은 EXTRACTED(사용자 확인 대기)를 거친다. 이 컬럼은
--   목록의 정렬·필터 키일 뿐이고, 어떤 흐름도 이 값으로 갈라지지 않는다.
--
-- 같은 테이블의 issued_on 이 선례다. 그것도 header_data 안에 있는 값을 컬럼으로 한 번 더
-- 두는데, 이유가 같다 — 기간 필터가 JSONB 를 못 본다. 정본은 여전히 header_data 이고
-- 여기는 사본이다.
-- =============================================================================

-- NULL 은 "아직 추출 결과가 없다" 다. false(검산 통과)와 구분해야 한다 —
-- NOT NULL DEFAULT false 로 두면 업로드 직후의 증빙이 "검산을 통과했다"로 읽힌다.
ALTER TABLE core.evidence ADD COLUMN needs_review BOOLEAN;

COMMENT ON COLUMN core.evidence.needs_review IS
    '현재 추출의 validation.needsReview 사본. 목록 정렬·필터용이며 상태가 아니다(합의 ③).';

-- 이미 확정된 추출들을 채운다. 이 백필이 없으면 V9 이전 증빙만 정렬에서 뒤로 밀린다.
-- ->> 는 text 를 주므로 'true'/'false' 가 그대로 boolean 으로 캐스팅된다. validation 이
-- 없거나 needsReview 키가 없으면 NULL 이 되고, 그게 맞다.
UPDATE core.evidence e
   SET needs_review = (x.header_data -> 'validation' ->> 'needsReview')::boolean
  FROM core.evidence_extraction x
 WHERE x.evidence_id = e.id
   AND x.is_current;

-- 검토 대기 목록의 실제 조회 모양이다 — 사업장 + 상태로 좁힌 뒤 검산 걸린 것 먼저.
-- 부분 인덱스로 두는 것은 CONFIRMED 가 쌓여도 이 인덱스가 커지지 않게 하기 위해서다.
CREATE INDEX ix_evidence_review ON core.evidence (business_id, needs_review DESC, created_at DESC)
    WHERE processing_status = 'EXTRACTED';
