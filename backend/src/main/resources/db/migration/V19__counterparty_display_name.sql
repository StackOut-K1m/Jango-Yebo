-- =============================================================================
-- 거래처 사전에 표시 이름을 둔다 (API 8.6 displayName)
--
-- **한 칸이 두 일을 겸하고 있었다.** canonical_name 은 거래 적요 원문이고 동시에 매칭
-- 열쇠다. 8.6 이 그 값을 사장님이 고칠 수 있게 하는데, 고치는 순간 매칭이 깨진다 —
-- 적요는 여전히 `우아한형제들` 로 들어오는데 열쇠가 `배달의민족` 이 되기 때문이다.
--
-- 그래서 나눈다.
--
--   canonical_name  매칭 열쇠. 은행이 찍는 적요 원문. 사용자가 고칠 수 없다
--   display_name    화면에 보이는 이름. 사용자가 고친다. 매칭에 쓰이지 않는다
--
-- 명세 8.6 의 응답이 이미 이 구분을 하고 있다 —
-- displayName "배달의민족" · matchPatterns ["우아한형제들", "우아한형제들(주)"].
-- matchPatterns 는 지금 canonical_name 한 줄이고, 별칭 배열은 표기가 갈리는 거래처가
-- 실제로 관측되면 그때 붙인다(08장 정정 참조).
--
-- 기존 행은 적요를 그대로 표시 이름으로 삼는다. 사람이 고치기 전까지는 그것이 사장님이
-- 통장에서 보던 문자열이라 가장 덜 낯설다.
-- =============================================================================

ALTER TABLE core.counterparty
    ADD COLUMN display_name VARCHAR(255);

UPDATE core.counterparty SET display_name = canonical_name WHERE display_name IS NULL;

ALTER TABLE core.counterparty
    ALTER COLUMN display_name SET NOT NULL;

COMMENT ON COLUMN core.counterparty.display_name IS
    '화면에 보이는 거래처명(API displayName). 사용자가 고친다 — 매칭 열쇠는 canonical_name 이다';

-- 8.6 의 q 검색은 표시 이름과 적요 양쪽을 본다. 사장님이 「배달」로 찾든 「우아한」으로 찾든
-- 같은 줄이 나와야 한다.
CREATE INDEX ix_cp_display_name ON core.counterparty (business_id, display_name);
