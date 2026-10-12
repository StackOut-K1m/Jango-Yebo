package com.jangoyebo.core.productsales;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 구성비·증감 계산 (API 14.5, P1-22).
 *
 * <p><b>순수 함수다.</b> DB·시계·HTTP 를 건드리지 않는다 — 예측 엔진과 같은 이유로,
 * 숫자 규칙을 Docker 없이 증명할 수 있어야 하고 월별 리포트(15.3)와 What-if(11.1)가 같은
 * 계산을 재사용한다.
 *
 * <h2>지키는 규칙 셋</h2>
 *
 * <ol>
 *   <li><b>구성비 분모는 {@code COALESCE(billed, gross)} 다</b>(ERD §1.9). POS 가 인쇄하는
 *       점유율이 합계 기준임을 실측으로 확인했다 — 카페라떼 161,950/670,450 = 24.2% 가 화면과
 *       맞고, 총매출 기준 163,000/690,000 = 23.6% 는 틀린다.</li>
 *   <li><b>없는 열을 0 으로 채우지 않는다</b>(계약 요구 3). 58mm 인쇄물에는 총매출 열이 아예
 *       없는데 0 으로 두면 "총매출 0원인 상품"이 된다. 합계도 <b>전부 null 일 때만</b> null 이다.</li>
 *   <li><b>원가를 모르므로 수익성을 말하지 않는다.</b> 여기서 내는 것은 점유율·증감·할인율까지고
 *       마진은 P2-03 범위다.</li>
 * </ol>
 */
public final class ProductShares {

    /**
     * 비율의 자리수. 표시는 {@link #ratio} 가 뒤 0 을 떨어뜨린다.
     *
     * <p>3 인 것은 POS 인쇄물의 자리수에 맞춘 것이다 — 161,950/670,450 이 화면의 24.2% 와
     * 같아야 대조가 성립한다. ⚠ 80품목짜리 인쇄물의 꼬리에서는 0.05% 가 {@code 0.001} 로,
     * 그보다 작으면 {@code 0} 으로 떨어진다.
     */
    private static final int RATIO_SCALE = 3;

    private ProductShares() {
    }

    /**
     * 한 상품의 기간 합계. 저장 행이 아니라 <b>계산 입력</b>이라 엔티티와 따로 둔다.
     *
     * <p>금액이 전부 nullable 인 것은 인쇄물마다 있는 열이 다르기 때문이다.
     */
    public record Line(String productKey,
                       String productName,
                       BigDecimal quantity,
                       BigDecimal grossAmount,
                       BigDecimal discountAmount,
                       BigDecimal netAmount,
                       BigDecimal vatAmount,
                       BigDecimal billedAmount) {

        /** 구성비 분모에 들어갈 값. 둘 다 없는 행은 DB CHECK 이 막는다. */
        public BigDecimal shareBasisAmount() {
            return billedAmount != null ? billedAmount : grossAmount;
        }
    }

    /** 계산 결과 한 줄 (14.5 {@code items[]}). */
    public record Metrics(String productKey,
                          String productName,
                          BigDecimal quantity,
                          BigDecimal grossAmount,
                          BigDecimal discountAmount,
                          BigDecimal netAmount,
                          BigDecimal vatAmount,
                          BigDecimal billedAmount,
                          BigDecimal share,
                          BigDecimal discountRate,
                          BigDecimal changeRate,
                          BigDecimal contributionToChange) {
    }

    /** {@code BILLED} 인가 {@code GROSS} 인가 (14.5 {@code shareBasis}). */
    public enum Basis { BILLED, GROSS }

    /**
     * 같은 상품 키의 행들을 하나로 합친다.
     *
     * <p><b>여러 기간이 조회에 걸리면 여기서 합쳐진다.</b> 표시 이름은 <b>가장 마지막에 들어온
     * 것</b>을 쓴다 — 사용자가 표기를 고쳤으면 그쪽이 최신이다.
     *
     * @param lines 등록 순서대로. 순서가 이름 선택에 쓰인다
     */
    public static List<Line> merge(List<Line> lines) {
        Map<String, Line> byKey = new LinkedHashMap<>();
        for (Line line : lines) {
            byKey.merge(line.productKey(), line, (a, b) -> new Line(
                    a.productKey(),
                    b.productName(),
                    sum(a.quantity(), b.quantity()),
                    sum(a.grossAmount(), b.grossAmount()),
                    sum(a.discountAmount(), b.discountAmount()),
                    sum(a.netAmount(), b.netAmount()),
                    sum(a.vatAmount(), b.vatAmount()),
                    sum(a.billedAmount(), b.billedAmount())));
        }
        return List.copyOf(byKey.values());
    }

    /**
     * 기간 전체의 합계 (14.5 {@code totals}).
     *
     * <p>{@code productKey}·{@code productName} 은 의미가 없어 null 이다.
     */
    public static Line totals(List<Line> lines) {
        Line total = new Line(null, null, null, null, null, null, null, null);
        for (Line line : lines) {
            total = new Line(null, null,
                    sum(total.quantity(), line.quantity()),
                    sum(total.grossAmount(), line.grossAmount()),
                    sum(total.discountAmount(), line.discountAmount()),
                    sum(total.netAmount(), line.netAmount()),
                    sum(total.vatAmount(), line.vatAmount()),
                    sum(total.billedAmount(), line.billedAmount()));
        }
        return total;
    }

    /**
     * 구성비 기준.
     *
     * <p><b>한 기간 안에서 기준을 섞지 않는다</b>(ERD §1.9). 모든 행에 결제금액이 있을 때만
     * {@code BILLED} 이고, 하나라도 없으면 {@code GROSS} 로 내린다 — 섞으면 분모가 두 종류의
     * 금액을 더한 값이 되어 어느 쪽 기준이라고도 말할 수 없다.
     *
     * <p>비어 있으면 {@code BILLED} 다. 아무 행도 없을 때의 기준은 표시용일 뿐이다.
     */
    public static Basis basisOf(List<Line> lines) {
        return lines.stream().allMatch(l -> l.billedAmount() != null) ? Basis.BILLED : Basis.GROSS;
    }

    /**
     * 구성비와 증감을 계산한다.
     *
     * <p><b>분모는 「보이는 행의 합」이다.</b> 인쇄된 합계 행을 쓰지 않는다 — 인쇄물이 잘리면
     * 합계 행은 기간 전체를 담고 보이는 행은 일부라, 합계 행을 분모로 잡으면 구성비 총합이
     * 100% 가 되지 않는다. 잘렸다는 사실은 {@code checksumStatus} 가 따로 말한다.
     *
     * @param current  조회 기간의 상품별 합계
     * @param previous <b>직전 동일 길이 기간</b>의 상품별 합계. 등록이 없으면 빈 목록이고,
     *                 그때 {@code changeRate}·{@code contributionToChange} 는 둘 다 null 이다 —
     *                 비교 대상이 없는 것을 "전부 신규 성장"으로 말하면 안 된다
     */
    public static List<Metrics> metrics(List<Line> current, List<Line> previous) {
        BigDecimal denominator = current.stream()
                .map(Line::shareBasisAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, BigDecimal> before = new LinkedHashMap<>();
        previous.forEach(l -> before.put(l.productKey(), l.shareBasisAmount()));
        boolean comparable = !previous.isEmpty();

        List<Metrics> out = new ArrayList<>(current.size());
        for (Line line : current) {
            BigDecimal basis = line.shareBasisAmount();
            BigDecimal prior = before.get(line.productKey());

            out.add(new Metrics(
                    line.productKey(), line.productName(), line.quantity(),
                    line.grossAmount(), line.discountAmount(), line.netAmount(),
                    line.vatAmount(), line.billedAmount(),
                    divide(basis, denominator),
                    divide(line.discountAmount(), line.grossAmount()),
                    comparable ? divide(subtract(basis, prior), prior) : null,
                    comparable ? subtract(basis, prior) : null));
        }
        return out;
    }

    /**
     * 전부 null 일 때만 null 이다.
     *
     * <p>⚠ 한쪽만 null 이면 <b>있는 쪽만 더한다.</b> 여러 기간이 걸릴 때 어떤 기간에만 있던
     * 열이 부분 합계로 나올 수 있다는 뜻이고, 호출자가 그 사실을 경고로 알린다
     * ({@code PARTIAL_COLUMN}). 0 으로 채우는 쪽이 더 나쁘다 — 없는 열이 "0원"이 된다.
     */
    static BigDecimal sum(BigDecimal a, BigDecimal b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : a.add(b);
    }

    /** null 을 0 으로 보되, 둘 다 null 이면 null 이다. */
    private static BigDecimal subtract(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) {
            return null;
        }
        return (a == null ? BigDecimal.ZERO : a).subtract(b == null ? BigDecimal.ZERO : b);
    }

    /** 분모가 없거나 0 이면 null 이다 — 0 으로 나눈 값을 0 으로 표시하면 거짓말이 된다. */
    private static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return null;
        }
        return ratio(numerator.divide(denominator, RATIO_SCALE, RoundingMode.HALF_UP));
    }

    /** 뒤 0 을 떨어뜨리되 지수 표기를 만들지 않는다 — {@code 1E+1} 이 나가면 화면이 못 읽는다. */
    private static BigDecimal ratio(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0, RoundingMode.UNNECESSARY) : stripped;
    }
}
