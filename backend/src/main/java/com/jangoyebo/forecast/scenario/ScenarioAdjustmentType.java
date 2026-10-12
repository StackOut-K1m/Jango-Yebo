package com.jangoyebo.forecast.scenario;

/**
 * What-if 조정 유형 <b>8종</b> (API 11.1).
 *
 * <p>DB {@code ck_sa_type} 와 값이 같아야 한다 — {@code MigrationTests} 가 둘을 대조한다.
 * 옛 {@code MENU_PRICE_CHANGE} 는 폐기됐고 {@code MATERIAL_COST_CHANGE} 는 SRS v1.11 에서
 * 범위 밖으로 나갔다({@code V29}). <b>둘 다 되살리지 않는다.</b>
 */
public enum ScenarioAdjustmentType {

    /** 입금이 며칠 늦게 들어온다 (P0-09). */
    SETTLEMENT_DELAY,

    /** 특정 입금 패턴이 그만큼 덜 들어온다 (P0-09). 감소만 — 증가는 표현하지 않는다. */
    DEPOSIT_DECREASE,

    /** 예정에 없던 지출 한 건 (P0-09). */
    EXTRA_EXPENSE,

    /** 예정에 없던 입금 한 건 (P0-13). */
    EXTRA_DEPOSIT,

    /** 예정된 지출의 날짜를 옮긴다 (P0-13). */
    EXPENSE_DATE_SHIFT,

    /**
     * 매출 자체가 변해 <b>매출성 입금 전체</b>가 달라진다 (P0-13).
     *
     * <p>{@link #DEPOSIT_DECREASE} 와 <b>같은 것이 아니다</b>(11.1 이 표로 갈라 뒀다).
     * 저쪽은 특정 패턴이 덜 들어오는 것이고 이쪽은 매출 자체가 움직이는 것이며, 증감
     * 양방향이다. 하나로 합치면 두 가정을 조합해 비교하는 일이 불가능해진다.
     */
    SALES_CHANGE,

    /** 상품 하나의 판매량·단가로 총매출 배율을 환산한다 (P0-13 · P1-22 구성비 연동). */
    PRODUCT_SALES_CHANGE,

    /** 대출을 실행하면 곡선이 어떻게 되는가 (P1-14). 실행하지 않는다 — 미리 보기다. */
    LOAN_EXECUTION
}
