-- 공고 자격 조건(JSONB)의 배열 칸을 **안전하게** 읽는 헬퍼.
--
-- ## 왜 필요한가
--
-- `eligibility` 는 워커가 준 JSON 을 **검증 없이 그대로** 담는다
-- (`SupportProgramStructuringService.eligibilityOf`). 지금은 워커의 pydantic 이
-- `regions: list[str]` 을 강제해서 배열만 들어오지만, 그 하나가 유일한 방어다.
--
-- 배열이어야 할 칸에 스칼라가 한 번 들어가면 **`jsonb_array_length` 가 터지고**
--
--     ERROR: cannot get array length of a scalar
--
-- Postgres 가 트랜잭션을 통째로 중단시킨다. 그러면 Java 쪽에서 예외를 잡아도 소용이
-- 없다 — 이미 죽은 트랜잭션이라 커밋이 실패한다. 2026-09-17 고장 주입에서 공고 한
-- 건의 `regions` 를 문자열로 바꿨더니 **17.1 검색·17.3 추천·10.2 정산 공백이 한꺼번에
-- 500** 이 났다. 10.2 는 P0 다.
--
-- ## 왜 SQL 의 OR 가드로는 못 막나
--
--     AND (:region IS NULL
--          OR jsonb_array_length(...) = 0     -- ← 여기
--          OR EXISTS (...))
--
-- Postgres 는 `OR` 분기의 **평가 순서를 보장하지 않는다.** 앞 조건이 참이어도 뒤
-- 분기를 먼저 계산할 수 있어서, 지역 필터를 아예 안 줘도 터졌다. `CASE` 도 상수
-- 접기(constant folding) 예외가 있어 완전하지 않다.
--
-- 그래서 **순서에 기대지 않는다.** 함수가 값을 먼저 배열로 바꿔 주므로, 평가 순서가
-- 어떻든 `jsonb_array_length`·`jsonb_array_elements_text` 는 배열만 본다.
--
-- ## 배열이 아니면 빈 배열이다 — 「모르면 남긴다」
--
-- 스칼라를 오류로 다루지 않고 **「안 적혔다」로 읽는다.** 검색·추천의 기존 규칙이
-- 그렇다(`SupportProgramRecommendationService` 참조) — 조건을 안 적은 공고는 후보에서
-- 빼지 않는다. 형식이 깨진 값도 같은 취급을 하는 편이 일관되고, 무엇보다 <b>사장님이
-- 볼 수 있었던 지원금을 잃지 않는다.</b>
CREATE OR REPLACE FUNCTION core.jsonb_text_array(value JSONB)
    RETURNS JSONB
    LANGUAGE SQL
    IMMUTABLE
    PARALLEL SAFE
AS $$
-- NULL 도 빈 배열이다. 호출부에서 COALESCE 를 또 쓰지 않게 한다 —
-- jsonb_typeof(NULL) 은 NULL 이라 'array' 와 같지 않아 자연히 걸린다.
SELECT CASE WHEN jsonb_typeof(value) = 'array' THEN value ELSE '[]'::JSONB END
$$;

COMMENT ON FUNCTION core.jsonb_text_array(JSONB) IS
    'JSONB 값을 배열로 강제한다. 배열이 아니면 빈 배열 — 형식이 깨진 자격 조건 한 건이 '
    '검색·추천·정산 공백 조회를 한꺼번에 500 으로 만들지 않게 한다(V33 주석).';
