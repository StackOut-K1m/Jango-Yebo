package com.jangoyebo.forecast.scenario;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.core.Loans.RepaymentCycle;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What-if 파라미터 한 건 (API 11.1). <b>검증을 통과한 값</b>만 이 모양이 된다.
 *
 * <p>요청은 {@link ScenarioDtos.ParameterDto} 평면 record 로 받고
 * {@link ScenarioParameterParser} 가 여기로 옮긴다. 요청 자리에 이 sealed 계층을 그대로
 * 쓰지 않는 이유는 Jackson 폴리모픽 역직렬화가 <b>모르는 {@code type} 을 파싱 단계에서</b>
 * 던지기 때문이다 — 그러면 전역 핸들러가 「요청 본문을 읽을 수 없습니다」로 뭉개고,
 * 명세가 그 자리에 약속한 {@code SCENARIO_PARAM_INVALID} 를 낼 수 없다.
 *
 * <p>sealed 인 것은 {@link ScenarioLedgerTransformer} 의 switch 가 <b>빠짐없음을 컴파일러에게
 * 검사받기</b> 위해서다. 9번째 유형을 더하면 변환기가 컴파일되지 않는다.
 */
public sealed interface ScenarioParameter {

    ScenarioAdjustmentType type();

    /**
     * 정산 지연.
     *
     * @param patternId 생략하면 <b>패턴이 만든 예정 입금 전체</b>다(11.1 "입금 패턴 전체")
     */
    record SettlementDelay(Long patternId, int delayDays) implements ScenarioParameter {
        @Override public ScenarioAdjustmentType type() {
            return ScenarioAdjustmentType.SETTLEMENT_DELAY;
        }
    }

    /**
     * 입금 감소.
     *
     * @param rate 0~1. 10% 감소가 {@code 0.1} 이다(공통규약 2.2 — 비율은 소수)
     */
    record DepositDecrease(BigDecimal rate, Long patternId) implements ScenarioParameter {
        @Override public ScenarioAdjustmentType type() {
            return ScenarioAdjustmentType.DEPOSIT_DECREASE;
        }
    }

    /**
     * 추가 지출·입금. <b>두 유형이 한 record 다</b> — 필드가 같고 방향만 다르다
     * (프론트 {@code ExtraFlowParameter} 도 같은 모양이다).
     */
    record ExtraFlow(ScenarioAdjustmentType type, LocalDate date, BigDecimal amount, String title)
            implements ScenarioParameter {

        public FlowType flowType() {
            return type == ScenarioAdjustmentType.EXTRA_DEPOSIT ? FlowType.DEPOSIT : FlowType.WITHDRAWAL;
        }
    }

    /** 지출일 변경. 대상은 <b>예정 지출</b>이어야 한다. */
    record ExpenseDateShift(Long plannedTransactionId, LocalDate newDate) implements ScenarioParameter {
        @Override public ScenarioAdjustmentType type() {
            return ScenarioAdjustmentType.EXPENSE_DATE_SHIFT;
        }
    }

    /**
     * 매출 변화.
     *
     * @param rate 음수 허용. −5% 가 {@code -0.05} 다
     */
    record SalesChange(BigDecimal rate) implements ScenarioParameter {
        @Override public ScenarioAdjustmentType type() {
            return ScenarioAdjustmentType.SALES_CHANGE;
        }
    }

    /**
     * 상품 매출 변화.
     *
     * @param productKey    정규화된 집계 키. 화면이 보낸 표시 이름을 {@code ProductSalesShares.keyOf}
     *                      로 통과시킨 값이다
     * @param productName   요청 원문. 경고 문장에 쓴다 — 사용자가 적은 글자로 돌려줘야 읽힌다
     * @param quantityRate  판매량 변화율. 둘 중 하나는 있어야 한다
     * @param priceRate     단가 변화율
     */
    record ProductSalesChange(String productKey, String productName,
                              BigDecimal quantityRate, BigDecimal priceRate)
            implements ScenarioParameter {

        @Override public ScenarioAdjustmentType type() {
            return ScenarioAdjustmentType.PRODUCT_SALES_CHANGE;
        }

        /** 상품 배율 {@code (1+quantityRate)(1+priceRate)} (11.1 ①). 빈 쪽은 0 으로 본다. */
        public BigDecimal productMultiplier() {
            return BigDecimal.ONE.add(quantityRate == null ? BigDecimal.ZERO : quantityRate)
                    .multiply(BigDecimal.ONE.add(priceRate == null ? BigDecimal.ZERO : priceRate));
        }
    }

    /**
     * 대출 실행 미리 보기.
     *
     * @param interestRate 연 금리를 <b>비율</b>로 받는다({@code 0.045} = 4.5%). 공통규약 2.2 이고
     *                     16.1·16.3 응답과 같은 단위다 — 엔진({@code LoanRepaymentSchedule})은
     *                     퍼센트를 받으므로 경계에서 100 을 곱한다
     */
    record LoanExecution(BigDecimal amount, BigDecimal interestRate, int loanPeriodDays,
                         RepaymentCycle repaymentCycle, LocalDate disbursementDate)
            implements ScenarioParameter {

        @Override public ScenarioAdjustmentType type() {
            return ScenarioAdjustmentType.LOAN_EXECUTION;
        }
    }
}
