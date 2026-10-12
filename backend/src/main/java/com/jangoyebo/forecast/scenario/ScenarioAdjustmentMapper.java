package com.jangoyebo.forecast.scenario;

import com.jangoyebo.core.Loans.RepaymentCycle;
import com.jangoyebo.forecast.scenario.ScenarioAdjustment.Details;
import com.jangoyebo.forecast.scenario.ScenarioDtos.ParameterDto;

import java.math.BigDecimal;

/**
 * 파라미터 ↔ 저장 행 ↔ 응답 사이의 변환. <b>순수 함수다.</b>
 *
 * <p>세 방향이 필요한 이유가 있다.
 *
 * <ul>
 *   <li>{@link #toEntity} — 저장. 전용 컬럼이 있는 값은 컬럼에, 나머지는 {@code details} 에</li>
 *   <li>{@link #restore} — 재계산. 대상이 삭제됐으면 <b>계산에서 빼고 이유를 말한다</b></li>
 *   <li>{@link #toDto} — 에코. 대상이 삭제됐어도 <b>사용자가 저장한 그대로</b> 보여 준다.
 *       계산에서 빠졌다는 사실은 {@code warnings} 가 따로 말한다 — 파라미터 목록에서 조용히
 *       지우면 「내가 저장한 조건이 왜 없어졌지」가 된다</li>
 * </ul>
 */
public final class ScenarioAdjustmentMapper {

    private ScenarioAdjustmentMapper() {
    }

    // --- 저장 -------------------------------------------------------------------

    public static ScenarioAdjustment toEntity(Long scenarioId, ScenarioParameter parameter) {
        return switch (parameter) {
            case ScenarioParameter.SettlementDelay p -> new ScenarioAdjustment(
                    scenarioId, p.type(), null, p.patternId(), null, null, null,
                    p.delayDays(), null, details(b -> b.targetPatternId = p.patternId()));

            case ScenarioParameter.DepositDecrease p -> new ScenarioAdjustment(
                    scenarioId, p.type(), null, p.patternId(), null, null, p.rate(),
                    null, null, details(b -> b.targetPatternId = p.patternId()));

            case ScenarioParameter.ExtraFlow p -> new ScenarioAdjustment(
                    scenarioId, p.type(), null, null, p.flowType(), p.amount(), null,
                    null, p.date(), details(b -> b.title = p.title()));

            case ScenarioParameter.ExpenseDateShift p -> new ScenarioAdjustment(
                    scenarioId, p.type(), p.plannedTransactionId(), null, null, null, null,
                    null, p.newDate(),
                    details(b -> b.targetPlannedTransactionId = p.plannedTransactionId()));

            case ScenarioParameter.SalesChange p -> new ScenarioAdjustment(
                    scenarioId, p.type(), null, null, null, null, p.rate(), null, null, null);

            case ScenarioParameter.ProductSalesChange p -> new ScenarioAdjustment(
                    scenarioId, p.type(), null, null, null, null, null, null, null,
                    details(b -> {
                        b.productKey = p.productKey();
                        b.productName = p.productName();
                        b.quantityRate = p.quantityRate();
                        b.priceRate = p.priceRate();
                    }));

            case ScenarioParameter.LoanExecution p -> new ScenarioAdjustment(
                    scenarioId, p.type(), null, null, null, p.amount(), null, null,
                    p.disbursementDate(),
                    details(b -> {
                        b.interestRate = p.interestRate();
                        b.loanPeriodDays = p.loanPeriodDays();
                        b.repaymentCycle = p.repaymentCycle().name();
                    }));
        };
    }

    // --- 재계산 -----------------------------------------------------------------

    /**
     * 저장 행을 다시 파라미터로. <b>되살릴 수 없으면 이유를 말한다.</b>
     *
     * @param parameter 계산에 넣을 값. 되살리지 못했으면 {@code null}
     * @param warning   되살리지 못한 이유. 되살렸으면 {@code null}
     */
    public record Restored(ScenarioParameter parameter, String warning) {

        static Restored of(ScenarioParameter parameter) {
            return new Restored(parameter, null);
        }

        static Restored dropped(String warning) {
            return new Restored(null, warning);
        }
    }

    public static Restored restore(ScenarioAdjustment row) {
        Details details = row.getDetails() == null ? empty() : row.getDetails();

        return switch (row.getAdjustmentType()) {
            case SETTLEMENT_DELAY -> {
                if (targetPatternLost(row, details)) {
                    yield Restored.dropped("대상 입금 패턴이 삭제되어 정산 지연을 반영하지 않았습니다.");
                }
                yield Restored.of(new ScenarioParameter.SettlementDelay(
                        row.getTargetPatternId(), row.getDelayDays() == null ? 0 : row.getDelayDays()));
            }
            case DEPOSIT_DECREASE -> {
                if (targetPatternLost(row, details)) {
                    yield Restored.dropped("대상 입금 패턴이 삭제되어 입금 감소를 반영하지 않았습니다.");
                }
                yield Restored.of(new ScenarioParameter.DepositDecrease(
                        row.getPercentageValue(), row.getTargetPatternId()));
            }
            case EXTRA_EXPENSE, EXTRA_DEPOSIT -> Restored.of(new ScenarioParameter.ExtraFlow(
                    row.getAdjustmentType(), row.getChangedDate(), row.getAmountValue(),
                    details.title() == null ? "추가 거래" : details.title()));

            case EXPENSE_DATE_SHIFT -> {
                if (row.getTargetPlannedTransactionId() == null) {
                    // V32 의 ON DELETE SET NULL 이 비운 자리다. 대상이 없으면 옮길 것이 없다.
                    yield Restored.dropped("대상 예정 거래가 삭제되어 지출일 변경을 반영하지 않았습니다.");
                }
                yield Restored.of(new ScenarioParameter.ExpenseDateShift(
                        row.getTargetPlannedTransactionId(), row.getChangedDate()));
            }
            case SALES_CHANGE -> Restored.of(new ScenarioParameter.SalesChange(row.getPercentageValue()));

            case PRODUCT_SALES_CHANGE -> {
                if (details.productKey() == null) {
                    // details 없이 들어온 행(수기 INSERT). 무엇을 조정하라는 것인지 알 수 없다.
                    yield Restored.dropped("저장된 상품 정보를 읽을 수 없어 상품 매출 변화를 반영하지 않았습니다.");
                }
                yield Restored.of(new ScenarioParameter.ProductSalesChange(
                        details.productKey(),
                        details.productName() == null ? details.productKey() : details.productName(),
                        details.quantityRate(), details.priceRate()));
            }
            case LOAN_EXECUTION -> {
                if (details.interestRate() == null || details.loanPeriodDays() == null
                        || details.repaymentCycle() == null) {
                    yield Restored.dropped("저장된 대출 조건을 읽을 수 없어 대출 실행을 반영하지 않았습니다.");
                }
                yield Restored.of(new ScenarioParameter.LoanExecution(
                        row.getAmountValue(), details.interestRate(), details.loanPeriodDays(),
                        RepaymentCycle.valueOf(details.repaymentCycle()), row.getChangedDate()));
            }
        };
    }

    /**
     * 대상 패턴이 <b>지워졌나</b>. 처음부터 「패턴 전체」였던 것과 구분해야 한다.
     *
     * <p>컬럼만 보면 둘 다 {@code null} 이라 구분되지 않는다. 구분하지 못하면 대상 패턴이
     * 사라진 시나리오가 조용히 <b>입금 패턴 전체</b>로 넓어져, 사용자가 저장할 때 본 것보다
     * 훨씬 큰 변화가 곡선에 나타난다.
     */
    private static boolean targetPatternLost(ScenarioAdjustment row, Details details) {
        return row.getTargetPatternId() == null && details.targetPatternId() != null;
    }

    // --- 에코 -------------------------------------------------------------------

    /** 저장 행 → 응답. 대상이 삭제됐으면 {@code details} 에 남은 원래 id 를 보여 준다. */
    public static ParameterDto toDto(ScenarioAdjustment row) {
        Details details = row.getDetails() == null ? empty() : row.getDetails();
        Long patternId = row.getTargetPatternId() != null
                ? row.getTargetPatternId() : details.targetPatternId();
        Long plannedId = row.getTargetPlannedTransactionId() != null
                ? row.getTargetPlannedTransactionId() : details.targetPlannedTransactionId();

        return switch (row.getAdjustmentType()) {
            case SETTLEMENT_DELAY -> dto(row, b -> {
                b.patternId = patternId;
                b.delayDays = row.getDelayDays();
            });
            case DEPOSIT_DECREASE -> dto(row, b -> {
                b.patternId = patternId;
                b.rate = row.getPercentageValue();
            });
            case EXTRA_EXPENSE, EXTRA_DEPOSIT -> dto(row, b -> {
                b.date = row.getChangedDate();
                b.amount = won(row.getAmountValue());
                b.title = details.title();
            });
            case EXPENSE_DATE_SHIFT -> dto(row, b -> {
                b.plannedTransactionId = plannedId;
                b.newDate = row.getChangedDate();
            });
            case SALES_CHANGE -> dto(row, b -> b.rate = row.getPercentageValue());
            case PRODUCT_SALES_CHANGE -> dto(row, b -> {
                b.productKey = details.productKey();
                b.quantityRate = details.quantityRate();
                b.priceRate = details.priceRate();
            });
            case LOAN_EXECUTION -> dto(row, b -> {
                b.amount = won(row.getAmountValue());
                b.interestRate = details.interestRate();
                b.loanPeriodDays = details.loanPeriodDays();
                b.repaymentCycle = details.repaymentCycle();
                b.disbursementDate = row.getChangedDate();
            });
        };
    }

    /** 방금 검증한 파라미터 → 응답. 정규화한 값(비율 자리수·다듬은 제목)이 그대로 나간다. */
    public static ParameterDto toDto(ScenarioParameter parameter) {
        return switch (parameter) {
            case ScenarioParameter.SettlementDelay p -> dto(p.type(), b -> {
                b.patternId = p.patternId();
                b.delayDays = p.delayDays();
            });
            case ScenarioParameter.DepositDecrease p -> dto(p.type(), b -> {
                b.patternId = p.patternId();
                b.rate = p.rate();
            });
            case ScenarioParameter.ExtraFlow p -> dto(p.type(), b -> {
                b.date = p.date();
                b.amount = p.amount();
                b.title = p.title();
            });
            case ScenarioParameter.ExpenseDateShift p -> dto(p.type(), b -> {
                b.plannedTransactionId = p.plannedTransactionId();
                b.newDate = p.newDate();
            });
            case ScenarioParameter.SalesChange p -> dto(p.type(), b -> b.rate = p.rate());
            case ScenarioParameter.ProductSalesChange p -> dto(p.type(), b -> {
                b.productKey = p.productKey();
                b.quantityRate = p.quantityRate();
                b.priceRate = p.priceRate();
            });
            case ScenarioParameter.LoanExecution p -> dto(p.type(), b -> {
                b.amount = p.amount();
                b.interestRate = p.interestRate();
                b.loanPeriodDays = p.loanPeriodDays();
                b.repaymentCycle = p.repaymentCycle().name();
                b.disbursementDate = p.disbursementDate();
            });
        };
    }

    // --- 빌더 -------------------------------------------------------------------
    //
    // record 필드가 열다섯 개라 위치 인자로 쓰면 어느 자리가 무엇인지 읽을 수 없고, 한 칸
    // 밀려 써도 컴파일러가 잡지 못한다(같은 타입이 연달아 있다). 이름으로 채운다.

    private static ParameterDto dto(ScenarioAdjustment row, java.util.function.Consumer<Fields> fill) {
        return dto(row.getAdjustmentType(), fill);
    }

    private static ParameterDto dto(ScenarioAdjustmentType type, java.util.function.Consumer<Fields> fill) {
        Fields f = new Fields();
        fill.accept(f);
        return new ParameterDto(type.name(), f.patternId, f.delayDays, f.rate, f.date, f.amount,
                f.title, f.plannedTransactionId, f.newDate, f.productKey, f.quantityRate,
                f.priceRate, f.interestRate, f.loanPeriodDays, f.repaymentCycle, f.disbursementDate);
    }

    private static Details details(java.util.function.Consumer<DetailFields> fill) {
        DetailFields f = new DetailFields();
        fill.accept(f);
        return new Details(f.title, f.productKey, f.productName, f.quantityRate, f.priceRate,
                f.interestRate, f.loanPeriodDays, f.repaymentCycle,
                f.targetPatternId, f.targetPlannedTransactionId);
    }

    /**
     * 응답 금액은 정수 원이다 (공통규약 2.2 — 금액은 원 단위 정수).
     *
     * <p>DB 는 {@code NUMERIC(19,4)} 라 그대로 내보내면 {@code 2000000.0000} 이 나간다.
     * 요청은 정수로 받았는데 저장한 것을 다시 읽으면 소수가 되는 셈이라, 저장 전후로 같은
     * 값이 보이지 않는다.
     */
    private static BigDecimal won(BigDecimal value) {
        return value == null ? null : value.setScale(0, java.math.RoundingMode.HALF_UP);
    }

    private static Details empty() {
        return new Details(null, null, null, null, null, null, null, null, null, null);
    }

    private static final class Fields {
        private Long patternId;
        private Integer delayDays;
        private BigDecimal rate;
        private java.time.LocalDate date;
        private BigDecimal amount;
        private String title;
        private Long plannedTransactionId;
        private java.time.LocalDate newDate;
        private String productKey;
        private BigDecimal quantityRate;
        private BigDecimal priceRate;
        private BigDecimal interestRate;
        private Integer loanPeriodDays;
        private String repaymentCycle;
        private java.time.LocalDate disbursementDate;
    }

    private static final class DetailFields {
        private String title;
        private String productKey;
        private String productName;
        private BigDecimal quantityRate;
        private BigDecimal priceRate;
        private BigDecimal interestRate;
        private Integer loanPeriodDays;
        private String repaymentCycle;
        private Long targetPatternId;
        private Long targetPlannedTransactionId;
    }
}
