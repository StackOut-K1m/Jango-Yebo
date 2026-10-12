-- =============================================================================
-- What-if 파라미터에서 재료비 변화를 뺀다 — 9종 → 8종 (API 11.1)
--
-- V1 이 이 제약을 세울 때 MATERIAL_COST_CHANGE 의 근거는 「지표 변동률 × 재료비 비중」
-- 이었고(기술스택 §4.10), 그 지표가 P1-06 과 함께 나갔다(S15P21C105-194 · V20).
-- 남은 rate 만으로 다시 정의할 수도 있었지만 — 예정 거래 중 분류가 MATERIALS 인
-- 출금에 곱하는 방식 — **커버리지가 반쪽이다.** 패턴으로 안 잡힌 재료비는 잔여 흐름
-- 축에 들어가는데 그 축은 입금·출금 두 숫자뿐이라 분류가 없어 골라낼 수가 없다.
-- 상품 단위 매출 시뮬레이션(PRODUCT_SALES_CHANGE)이 실측 구성비를 근거로 삼는
-- 더 나은 도구라 그쪽을 남긴다.
--
-- 지우는 데이터가 없다. 시나리오(API 11장)는 백엔드 구현이 아직 없어
-- forecast.scenario_adjustment 에 행을 만드는 경로 자체가 없다. 그래서 백필도,
-- 기존 행 정리도 필요 없고 제약만 다시 건다.
--
-- MATERIALS(재료비) 거래 분류는 건드리지 않는다 — 그건 P0-02 이고 V2 시드다.
-- 재료비를 분류·집계하는 것과 「재료비가 오르면」을 시뮬레이션하는 것은 다른 기능이다.
-- =============================================================================

ALTER TABLE forecast.scenario_adjustment
    DROP CONSTRAINT ck_sa_type;

-- V1 의 목록에서 MATERIAL_COST_CHANGE 한 줄만 뺀 것이다. 나머지 8종은 그대로다.
ALTER TABLE forecast.scenario_adjustment
    ADD CONSTRAINT ck_sa_type CHECK (adjustment_type IN ('SETTLEMENT_DELAY', 'DEPOSIT_DECREASE',
                                                         'EXTRA_EXPENSE', 'EXTRA_DEPOSIT',
                                                         'EXPENSE_DATE_SHIFT', 'SALES_CHANGE',
                                                         'PRODUCT_SALES_CHANGE', 'LOAN_EXECUTION'));
