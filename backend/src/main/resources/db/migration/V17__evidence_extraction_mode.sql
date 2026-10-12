-- =============================================================================
-- 추출을 어느 모드로 돌렸는지 남긴다 (워커 계약 20.4 의 extractionMode)
--
-- 모드는 둘이다. THOROUGH 는 긴 영수증을 세로 타일로 잘라 다시 읽는다. 실측에서 오독
-- 3건 중 2건을 고쳤고, 열화 이미지의 품목명 exact 를 76.3 → 83.3% 로 올렸다
-- (ai/experiments/README.md). 대신 이미지 토큰이 타일 수만큼 늘어 전건에 쓰면 비용이
-- +52% 라 재추출에서만 켜기로 했다.
--
-- 그런데 어느 모드로 뽑은 결과인지 남기지 않아서, 「재추출이 실제로 고쳤나」를 실사용
-- 데이터로 답할 수 없었다. extractionMode 는 요청 필드라 콜백 본문에 없고, Job 파라미터에만
-- 있는데 그 Job 은 정리 배치가 지운다. 그래서 추출 행에 직접 남긴다.
--
-- 소급이 안 된다. 이 컬럼이 생기기 전에 쌓인 행은 어느 모드였는지 되살릴 방법이 없으므로
-- NULL 로 둔다. NULL 은 「모드가 없었다」가 아니라 「이 컬럼이 생기기 전이다」라는 뜻이다.
-- 그래서 NOT NULL 을 걸지 않는다 — 걸면 기존 행을 임의의 값으로 메워야 하고, 그 순간
-- 측정이 거짓말을 시작한다.
-- =============================================================================

ALTER TABLE core.evidence_extraction
    ADD COLUMN extraction_mode VARCHAR(20);

COMMENT ON COLUMN core.evidence_extraction.extraction_mode IS
    '추출을 돌린 모드. 값 집합은 워커 계약(20.4) extractionMode 와 같다. NULL 은 이 컬럼 이전 행이다';

-- 워커의 ExtractionMode Literal 과 같은 집합이다(ai/app/ocr/schemas.py).
-- 한쪽만 늘면 워커가 받는 값을 백엔드가 저장하지 못한다.
ALTER TABLE core.evidence_extraction
    ADD CONSTRAINT ck_ee_extraction_mode
        CHECK (extraction_mode IS NULL OR extraction_mode IN ('STANDARD', 'THOROUGH'));
