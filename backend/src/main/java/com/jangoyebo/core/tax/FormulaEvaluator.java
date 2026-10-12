package com.jangoyebo.core.tax;

import java.math.BigDecimal;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 서식 산식 평가기 (API 14.3 — {@code sections[].fields[].formula}).
 *
 * <p>서식 정의를 데이터로 두기로 했으므로 산식도 문자열로 온다 —
 * {@code "TOTAL_REVENUE - REVENUE_EXCLUDED + REVENUE_ADDED"}.
 *
 * <p><b>문법을 일부러 좁게 잡았다.</b> 식별자와 {@code +}·{@code -} 뿐이다. 별지 제74호서식의
 * 산식이 전부 가감이라 그 이상이 필요 없고, 넓히면 신고서 숫자를 만드는 자리에 임의 식을
 * 넣을 수 있게 된다. 문법을 벗어나면 <b>던진다</b> — 0 을 돌려주면 소득금액이 조용히 달라진다.
 *
 * <p><b>아직 계산되지 않은 필드를 참조해도 던진다.</b> 평가는 서식 선언 순서로 하는데
 * (수입금액 → 매출원가 → 경비 → 세무조정 → 소득금액), 그 순서는 시드가 지키는 암묵적
 * 약속이다. 시드를 고치다 순서가 어긋나면 없는 값이 0 으로 읽혀 <b>합계만 틀린 초안</b>이
 * 나온다. 여기서 터뜨려 그 경우를 테스트가 잡게 한다.
 */
final class FormulaEvaluator {

    /** 식별자 하나로 시작해서 (부호 + 식별자) 가 이어지는 형태만 받는다. */
    private static final Pattern GRAMMAR =
            Pattern.compile("[A-Z_][A-Z0-9_]*(\\s*[+-]\\s*[A-Z_][A-Z0-9_]*)*");

    private static final Pattern TOKEN = Pattern.compile("\\s*([+-])?\\s*([A-Z_][A-Z0-9_]*)");

    private FormulaEvaluator() {
    }

    /**
     * @param formula 가감식. {@code "A + B - C"}
     * @param values  지금까지 계산된 필드 값. {@code null} 값은 0 으로 본다 —
     *                사용자가 아직 안 적은 {@code USER_CONFIRM} 항목이 그렇다
     * @throws IllegalArgumentException 문법을 벗어났거나, 모르는 필드를 참조했을 때
     */
    static BigDecimal evaluate(String formula, Map<String, BigDecimal> values) {
        String trimmed = formula == null ? "" : formula.trim();
        if (!GRAMMAR.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "서식 산식이 허용 문법(식별자와 +, - )을 벗어났습니다: " + formula);
        }

        BigDecimal total = BigDecimal.ZERO;
        var matcher = TOKEN.matcher(trimmed);
        while (matcher.find()) {
            String sign = matcher.group(1);
            String key = matcher.group(2);
            if (!values.containsKey(key)) {
                throw new IllegalArgumentException(
                        "산식이 아직 계산되지 않은 항목을 참조합니다: %s (산식 %s). "
                                .formatted(key, formula)
                                + "서식 정의의 섹션 순서를 확인하세요.");
            }
            BigDecimal value = values.get(key);
            if (value == null) {
                value = BigDecimal.ZERO;
            }
            total = "-".equals(sign) ? total.subtract(value) : total.add(value);
        }
        return total;
    }
}
