package com.jangoyebo.forecast.scenario;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.core.Loans.RepaymentCycle;
import com.jangoyebo.forecast.scenario.ScenarioDtos.ParameterDto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * 평면 요청을 {@link ScenarioParameter} 로 옮기며 <b>형식과 범위</b>를 본다 (API 11.1).
 *
 * <p><b>순수 함수다.</b> DB 도 기준일도 보지 않는다 — 그래서 틀린 본문이 예측 재계산을
 * 예약하는 일이 없다. 소유권 검증과 기준일 대비 날짜 검증은 계산 직전에
 * {@link ScenarioService} 가 한다.
 *
 * <p><b>모든 실패는 {@code SCENARIO_PARAM_INVALID} 다.</b> 메시지가
 * {@code parameters[i].field} 로 자리를 가리키고 {@code detail} 에 같은 정보를 구조로 담는다.
 * 화면이 조건 카드를 여러 장 쌓아 보내므로 <b>몇 번째가 문제인지</b>가 없으면 사용자는
 * 어느 카드를 고쳐야 할지 알 수 없다.
 */
public final class ScenarioParameterParser {

    /** 정산 지연 일수. 30일 창을 통째로 넘기는 값도 시험 자체는 의미가 있어 넉넉히 둔다. */
    private static final int DELAY_DAYS_MAX = 90;

    /** 금액 상한. 원 단위 {@code NUMERIC(19,4)} 안에서 곱셈 여유를 남긴다. */
    private static final BigDecimal AMOUNT_MAX = new BigDecimal("1000000000000");

    /** 증감률 상한. 6배까지는 「가정」이지만 그 위는 입력 실수(퍼센트를 비율로 착각)에 가깝다. */
    private static final BigDecimal RATE_MAX = new BigDecimal("5");

    private static final BigDecimal MINUS_ONE = new BigDecimal("-1");

    /** 연 금리 상한(비율). 금융망 대출이 0.1~20% 다. */
    private static final BigDecimal INTEREST_RATE_MAX = new BigDecimal("0.2");

    private static final int LOAN_PERIOD_DAYS_MAX = 3650;

    private static final int TITLE_MAX = 100;

    /** 유형별로 <b>쓰는</b> 필드. 여기 없는 값을 보내면 400 이다 — {@link #rejectUnusedFields} 참조. */
    private static final Map<ScenarioAdjustmentType, Set<String>> ALLOWED_FIELDS = Map.of(
            ScenarioAdjustmentType.SETTLEMENT_DELAY, Set.of("patternId", "delayDays"),
            ScenarioAdjustmentType.DEPOSIT_DECREASE, Set.of("patternId", "rate"),
            ScenarioAdjustmentType.EXTRA_EXPENSE, Set.of("date", "amount", "title"),
            ScenarioAdjustmentType.EXTRA_DEPOSIT, Set.of("date", "amount", "title"),
            ScenarioAdjustmentType.EXPENSE_DATE_SHIFT, Set.of("plannedTransactionId", "newDate"),
            ScenarioAdjustmentType.SALES_CHANGE, Set.of("rate"),
            ScenarioAdjustmentType.PRODUCT_SALES_CHANGE, Set.of("productKey", "quantityRate", "priceRate"),
            ScenarioAdjustmentType.LOAN_EXECUTION,
            Set.of("amount", "interestRate", "loanPeriodDays", "repaymentCycle", "disbursementDate"));

    private ScenarioParameterParser() {
    }

    /**
     * @param keyOf 상품명 → 집계 키. 정규화 규칙은 {@code core} 한 곳에만 살아야 해서
     *              함수로 받는다({@code ProductSalesShares.keyOf})
     */
    public static List<ScenarioParameter> parse(List<ParameterDto> raw, UnaryOperator<String> keyOf) {
        List<ScenarioParameter> parsed = new ArrayList<>(raw.size());
        Set<Long> shiftedPlannedIds = new HashSet<>();
        Set<String> productKeys = new HashSet<>();

        for (int i = 0; i < raw.size(); i++) {
            ParameterDto dto = raw.get(i);
            if (dto == null) {
                throw invalid(i, null, "type", "파라미터가 비어 있습니다.");
            }
            ScenarioAdjustmentType type = typeOf(i, dto.type());
            rejectUnusedFields(i, type, dto);

            parsed.add(switch (type) {
                case SETTLEMENT_DELAY -> settlementDelay(i, dto);
                case DEPOSIT_DECREASE -> depositDecrease(i, dto);
                case EXTRA_EXPENSE, EXTRA_DEPOSIT -> extraFlow(i, type, dto);
                case EXPENSE_DATE_SHIFT -> expenseDateShift(i, dto, shiftedPlannedIds);
                case SALES_CHANGE -> salesChange(i, dto);
                case PRODUCT_SALES_CHANGE -> productSalesChange(i, dto, keyOf, productKeys);
                case LOAN_EXECUTION -> loanExecution(i, dto);
            });
        }
        return List.copyOf(parsed);
    }

    // --- 유형별 -----------------------------------------------------------------

    private static ScenarioAdjustmentType typeOf(int index, String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid(index, null, "type", "파라미터 종류가 없습니다.");
        }
        try {
            return ScenarioAdjustmentType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw invalid(index, raw, "type", "지원하지 않는 파라미터입니다: " + raw);
        }
    }

    private static ScenarioParameter settlementDelay(int index, ParameterDto dto) {
        int delayDays = requiredInt(index, dto.type(), "delayDays", dto.delayDays());
        if (delayDays < 1 || delayDays > DELAY_DAYS_MAX) {
            throw invalid(index, dto.type(), "delayDays",
                    "1~%d 사이여야 합니다.".formatted(DELAY_DAYS_MAX));
        }
        return new ScenarioParameter.SettlementDelay(dto.patternId(), delayDays);
    }

    private static ScenarioParameter depositDecrease(int index, ParameterDto dto) {
        BigDecimal rate = requiredAmount(index, dto.type(), "rate", dto.rate());
        // 0 은 「아무것도 줄지 않는다」라 조건으로서 뜻이 없고, 1 을 넘으면 입금이 음수가 된다.
        if (rate.signum() <= 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw invalid(index, dto.type(), "rate", "0 보다 크고 1 이하여야 합니다. 10% 감소는 0.1 입니다.");
        }
        return new ScenarioParameter.DepositDecrease(normalizeRate(rate), dto.patternId());
    }

    private static ScenarioParameter extraFlow(int index, ScenarioAdjustmentType type, ParameterDto dto) {
        if (dto.date() == null) {
            throw invalid(index, dto.type(), "date", "날짜가 없습니다.");
        }
        BigDecimal amount = requiredAmount(index, dto.type(), "amount", dto.amount());
        if (amount.signum() <= 0) {
            // 방향은 type 이 말한다. 금액에 부호를 두지 않는다(공통규약 2.2).
            throw invalid(index, dto.type(), "amount", "0 보다 커야 합니다.");
        }
        if (amount.compareTo(AMOUNT_MAX) > 0) {
            throw invalid(index, dto.type(), "amount", "금액이 너무 큽니다.");
        }
        if (dto.title() == null || dto.title().isBlank()) {
            throw invalid(index, dto.type(), "title", "제목이 필요합니다.");
        }
        String title = dto.title().strip();
        if (title.length() > TITLE_MAX) {
            throw invalid(index, dto.type(), "title", "%d자 이하여야 합니다.".formatted(TITLE_MAX));
        }
        return new ScenarioParameter.ExtraFlow(type, dto.date(), amount, title);
    }

    private static ScenarioParameter expenseDateShift(int index, ParameterDto dto, Set<Long> seen) {
        if (dto.plannedTransactionId() == null) {
            throw invalid(index, dto.type(), "plannedTransactionId", "옮길 예정 거래가 없습니다.");
        }
        if (dto.newDate() == null) {
            throw invalid(index, dto.type(), "newDate", "새 날짜가 없습니다.");
        }
        if (!seen.add(dto.plannedTransactionId())) {
            // 두 번 옮기면 뒤엣것이 이기는데 사용자는 둘 다 반영됐다고 읽는다. 달력에서 같은
            // 칩을 두 번 끌면 화면이 하나로 합쳐 보내므로, 여기까지 오는 것은 실수다.
            throw invalid(index, dto.type(), "plannedTransactionId",
                    "같은 예정 거래를 두 번 옮길 수 없습니다.");
        }
        return new ScenarioParameter.ExpenseDateShift(dto.plannedTransactionId(), dto.newDate());
    }

    private static ScenarioParameter salesChange(int index, ParameterDto dto) {
        BigDecimal rate = requiredAmount(index, dto.type(), "rate", dto.rate());
        // −1 은 매출이 0 이 되는 것이고 그 아래는 뜻이 없다. 위쪽은 입력 실수를 막는 선이다.
        if (rate.compareTo(MINUS_ONE) < 0 || rate.compareTo(RATE_MAX) > 0) {
            throw invalid(index, dto.type(), "rate",
                    "-1 이상 %s 이하여야 합니다. 5%% 감소는 -0.05 입니다."
                            .formatted(RATE_MAX.toPlainString()));
        }
        return new ScenarioParameter.SalesChange(normalizeRate(rate));
    }

    private static ScenarioParameter productSalesChange(int index, ParameterDto dto,
                                                        UnaryOperator<String> keyOf, Set<String> seen) {
        if (dto.productKey() == null || dto.productKey().isBlank()) {
            throw invalid(index, dto.type(), "productKey", "상품이 없습니다.");
        }
        if (dto.quantityRate() == null && dto.priceRate() == null) {
            throw invalid(index, dto.type(), "quantityRate",
                    "quantityRate 나 priceRate 중 하나는 있어야 합니다.");
        }
        BigDecimal quantityRate = boundedRate(index, dto.type(), "quantityRate", dto.quantityRate());
        BigDecimal priceRate = boundedRate(index, dto.type(), "priceRate", dto.priceRate());

        String key = keyOf.apply(dto.productKey());
        if (!seen.add(key)) {
            // 11.1 은 상품마다 배율을 한 번씩 만들어 합산한다. 같은 상품이 두 번 들어오면
            // 그 상품의 구성비가 두 번 세어져 총매출 배율이 부풀어 오른다.
            throw invalid(index, dto.type(), "productKey",
                    "같은 상품을 두 번 조정할 수 없습니다: " + dto.productKey());
        }
        return new ScenarioParameter.ProductSalesChange(key, dto.productKey().strip(),
                quantityRate, priceRate);
    }

    private static ScenarioParameter loanExecution(int index, ParameterDto dto) {
        BigDecimal amount = requiredAmount(index, dto.type(), "amount", dto.amount());
        if (amount.signum() <= 0) {
            throw invalid(index, dto.type(), "amount", "0 보다 커야 합니다.");
        }
        if (amount.compareTo(AMOUNT_MAX) > 0) {
            throw invalid(index, dto.type(), "amount", "금액이 너무 큽니다.");
        }
        BigDecimal interestRate = requiredAmount(index, dto.type(), "interestRate", dto.interestRate());
        if (interestRate.signum() < 0 || interestRate.compareTo(INTEREST_RATE_MAX) > 0) {
            // 4.5 를 그대로 보내는 실수가 가장 흔하다 — 연 450% 로 계산돼 곡선이 무너진다.
            throw invalid(index, dto.type(), "interestRate",
                    "금리는 비율로 보냅니다 — 연 4.5% 는 0.045 입니다.");
        }
        int loanPeriodDays = requiredInt(index, dto.type(), "loanPeriodDays", dto.loanPeriodDays());
        if (loanPeriodDays < 1 || loanPeriodDays > LOAN_PERIOD_DAYS_MAX) {
            throw invalid(index, dto.type(), "loanPeriodDays",
                    "1~%d 사이여야 합니다.".formatted(LOAN_PERIOD_DAYS_MAX));
        }
        if (dto.disbursementDate() == null) {
            throw invalid(index, dto.type(), "disbursementDate", "실행일이 없습니다.");
        }
        return new ScenarioParameter.LoanExecution(amount, interestRate, loanPeriodDays,
                repaymentCycleOf(index, dto), dto.disbursementDate());
    }

    private static RepaymentCycle repaymentCycleOf(int index, ParameterDto dto) {
        if (dto.repaymentCycle() == null || dto.repaymentCycle().isBlank()) {
            throw invalid(index, dto.type(), "repaymentCycle", "상환 방식이 없습니다.");
        }
        try {
            return RepaymentCycle.valueOf(dto.repaymentCycle());
        } catch (IllegalArgumentException e) {
            throw invalid(index, dto.type(), "repaymentCycle",
                    "지원하지 않는 상환 방식입니다: " + dto.repaymentCycle());
        }
    }

    // --- 공통 -------------------------------------------------------------------

    /**
     * 유형이 쓰지 않는 필드가 섞여 있으면 막는다.
     *
     * <p>무시하는 편이 관대해 보이지만, 무시하면 <b>사용자가 넣은 조건이 조용히 사라진다</b> —
     * 「정산 지연에 감소율 20%」를 보낸 클라이언트는 그 20% 가 반영됐다고 읽는데 곡선은
     * 지연만 반영한 값이다. 숫자를 보여 주는 화면에서 그 차이는 드러나지 않는다.
     */
    private static void rejectUnusedFields(int index, ScenarioAdjustmentType type, ParameterDto dto) {
        Set<String> allowed = ALLOWED_FIELDS.get(type);
        for (String field : presentFields(dto)) {
            if (!allowed.contains(field)) {
                throw invalid(index, dto.type(), field, "%s 에는 쓰지 않는 필드입니다.".formatted(type));
            }
        }
    }

    private static List<String> presentFields(ParameterDto dto) {
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("patternId", dto.patternId());
        all.put("delayDays", dto.delayDays());
        all.put("rate", dto.rate());
        all.put("date", dto.date());
        all.put("amount", dto.amount());
        all.put("title", dto.title());
        all.put("plannedTransactionId", dto.plannedTransactionId());
        all.put("newDate", dto.newDate());
        all.put("productKey", dto.productKey());
        all.put("quantityRate", dto.quantityRate());
        all.put("priceRate", dto.priceRate());
        all.put("interestRate", dto.interestRate());
        all.put("loanPeriodDays", dto.loanPeriodDays());
        all.put("repaymentCycle", dto.repaymentCycle());
        all.put("disbursementDate", dto.disbursementDate());

        List<String> present = new ArrayList<>();
        all.forEach((key, value) -> {
            if (value != null) {
                present.add(key);
            }
        });
        return present;
    }

    private static int requiredInt(int index, String type, String field, Integer value) {
        if (value == null) {
            throw invalid(index, type, field, "값이 없습니다.");
        }
        return value;
    }

    private static BigDecimal requiredAmount(int index, String type, String field, BigDecimal value) {
        if (value == null) {
            throw invalid(index, type, field, "값이 없습니다.");
        }
        return value;
    }

    private static BigDecimal boundedRate(int index, String type, String field, BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.compareTo(MINUS_ONE) < 0 || value.compareTo(RATE_MAX) > 0) {
            throw invalid(index, type, field,
                    "-1 이상 %s 이하여야 합니다.".formatted(RATE_MAX.toPlainString()));
        }
        return normalizeRate(value);
    }

    /** {@code percentage_value NUMERIC(7,4)} 에 맞춘다 — 저장한 값과 에코한 값이 같아야 한다. */
    private static BigDecimal normalizeRate(BigDecimal rate) {
        return rate.setScale(4, RoundingMode.HALF_UP);
    }

    static BusinessException invalid(int index, String type, String field, String message) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("index", index);
        detail.put("field", field);
        if (type != null) {
            detail.put("type", type);
        }
        return new BusinessException(ErrorCode.SCENARIO_PARAM_INVALID,
                "parameters[%d].%s: %s".formatted(index, field, message), detail);
    }
}
