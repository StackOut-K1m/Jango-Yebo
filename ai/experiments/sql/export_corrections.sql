-- 사용자 수정 이력 내보내기 — `experiments/analyze_corrections.py` 의 입력을 만든다.
--
--   psql "$DATABASE_URL" -At -f ai/experiments/sql/export_corrections.sql > corrections.jsonl
--   uv run --frozen python -X utf8 -m experiments.analyze_corrections corrections.jsonl
--
-- **한 줄 = 검토 1건**(사용자가 확인한 추출 하나). 수정이 없어도 한 줄이 나와야 한다 —
-- 고친 것만 모으면 분모가 없어 정확도를 낼 수 없다. 그래서 `evidence_correction` 이 아니라
-- `evidence_extraction` 에서 시작하고 LEFT JOIN 한다. 합의 ⑦ 이 `confirmed_at` 을 "정확도의
-- 분모" 라고 부른 이유가 이것이다.
--
-- `confirmed_at IS NULL` 은 아직 사람이 안 본 추출이라 제외한다. 봤는데 안 고친 것(분모에 들어감)
-- 과 아직 안 본 것(분모 밖)은 다르다.
--
-- ⚠ `extractionMode` 는 비운다. 요청 필드(20.4 v1.17)라 콜백에도 테이블에도 없다 — 그래서
--   분석기의 네 번째 질문(「재추출이 실제로 고쳤나」)은 지금 답이 안 나온다. 답하려면
--   evidence_extraction 에 컬럼 하나가 필요하다.
SELECT json_build_object(
    'extractionId',   x.id,
    'evidenceId',     x.evidence_id,
    'modelName',      x.model_name,
    'promptVersion',  x.prompt_version,
    'extractionMode', NULL,                       -- 위 ⚠ 참고
    'validation',     x.header_data -> 'validation',
    'fieldConfidence', COALESCE(x.header_data -> 'fieldConfidence', '{}'::jsonb),
    'corrections',    COALESCE(c.corrections, '[]'::json),
    'confirmedAt',    x.confirmed_at        -- Postgres 가 ISO-8601 로 직렬화한다
)
FROM core.evidence_extraction x
LEFT JOIN LATERAL (
    -- 컬럼 이름을 분석기 계약(before·after)에 맞춘다. DB 는 before_value·after_value 다.
    SELECT json_agg(json_build_object(
        'field',  r.field,
        'before', r.before_value,
        'after',  r.after_value
    ) ORDER BY r.corrected_at) AS corrections
    FROM core.evidence_correction r
    WHERE r.extraction_id = x.id
) c ON TRUE
WHERE x.confirmed_at IS NOT NULL
  AND x.is_current                                -- 재추출된 옛 행은 세지 않는다
ORDER BY x.confirmed_at;
