package com.jangoyebo.forecast.engine;

import com.jangoyebo.forecast.engine.CreditGradeProjection.Band;
import com.jangoyebo.forecast.engine.CreditGradeProjection.CurvePoint;
import com.jangoyebo.forecast.engine.CreditGradeProjection.MonthlyEstimate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 등급 예측 (API 16.2, P1-14). Docker 불필요 — 곡선 점 목록만 받는 순수 함수다.
 */
class CreditGradeProjectionTests {

    /** 금융망 실측 구간(2026-09-15 조회). 자산이 큰 등급부터. */
    private static final List<Band> 구간 = List.of(
            new Band("A", 원("100000000")),
            new Band("B", 원("80000000")),
            new Band("C", 원("50000000")),
            new Band("D", 원("30000000")),
            new Band("E", BigDecimal.ZERO));

    private static BigDecimal 원(String v) {
        return new BigDecimal(v);
    }

    /** 9/15 부터 하루 {@code 일변화}씩 움직이는 곡선. */
    private static List<CurvePoint> 곡선(String 시작잔액, String 일변화, int 일수) {
        List<CurvePoint> points = new ArrayList<>();
        BigDecimal balance = 원(시작잔액);
        for (int i = 1; i <= 일수; i++) {
            balance = balance.add(원(일변화));
            points.add(new CurvePoint(LocalDate.of(2026, 9, 15).plusDays(i), balance));
        }
        return points;
    }

    @Test
    void 곡선의_변화분만_현재_자산에_얹는다() {
        // 등급 자산은 「이 사람의 모든 계좌」이고 곡선은 「한 사업장」이다. 범위가 달라
        // 곡선 값을 자산으로 그대로 쓸 수 없다 — 움직인 만큼만 더한다.
        List<MonthlyEstimate> p = CreditGradeProjection.project(
                곡선("5000000", "-100000", 15),   // 9/16~9/30, 하루 −10만
                원("5000000"), 원("55744615"), 구간);

        assertThat(p).hasSize(1);
        // 9/30 곡선 = 500만 − 150만 = 350만. 변화분 −150만을 자산에 얹는다.
        assertThat(p.getFirst().estimatedAsset()).isEqualByComparingTo("54244615");
        assertThat(p.getFirst().month()).isEqualTo("2026-09");
    }

    @Test
    void 등급이_떨어지는_달을_짚는다() {
        // 이 화면의 질문은 「언제 떨어지나」다. C 하한 5,000만을 지나는 달이 답이다.
        List<MonthlyEstimate> p = CreditGradeProjection.project(
                곡선("5000000", "-300000", 45),   // 9/16~10/30, 하루 −30만
                원("5000000"), 원("55744615"), 구간);

        assertThat(p).hasSize(2);
        // 9/30 은 15일치 −450만 → 자산 5,124만, 아직 C 하한(5,000만) 위다.
        assertThat(p.get(0).month()).isEqualTo("2026-09");
        assertThat(p.get(0).estimatedGrade()).isEqualTo("C");
        assertThat(p.get(0).estimatedAsset()).isEqualByComparingTo("51244615");
        // 10/30 은 45일치 −1,350만 → 자산 4,224만. 여기서 떨어진다.
        assertThat(p.get(1).month()).isEqualTo("2026-10");
        assertThat(p.get(1).estimatedGrade()).isEqualTo("D");
        assertThat(p.get(1).estimatedAsset()).isEqualByComparingTo("42244615");
    }

    @Test
    void 달마다_마지막_예측일을_대표로_쓴다() {
        // 날마다 등급을 매기면 구간 경계에서 하루 단위로 오르내려 읽을 수 없다.
        List<MonthlyEstimate> p = CreditGradeProjection.project(
                곡선("5000000", "-100000", 45), 원("5000000"), 원("55744615"), 구간);

        // 9월은 9/30(15일치 −150만), 10월은 10/30(45일치 −450만)
        assertThat(p.get(0).estimatedAsset()).isEqualByComparingTo("54244615");
        assertThat(p.get(1).estimatedAsset()).isEqualByComparingTo("51244615");
    }

    @Test
    void 자산이_늘면_등급이_오른다() {
        List<MonthlyEstimate> p = CreditGradeProjection.project(
                곡선("5000000", "2000000", 15), 원("5000000"), 원("55744615"), 구간);

        // 55,744,615 + 3,000만 = 85,744,615 → B
        assertThat(p.getFirst().estimatedGrade()).isEqualTo("B");
    }

    @Test
    void 곡선이_공백을_그려도_등급이_깨지지_않는다() {
        // 잔액이 음수로 내려가면 자산도 음수가 될 수 있다. 「음수 자산」은 등급 체계에 없다.
        List<MonthlyEstimate> p = CreditGradeProjection.project(
                곡선("5000000", "-5000000", 20), 원("5000000"), 원("3744615"), 구간);

        assertThat(p).allSatisfy(e -> assertThat(e.estimatedGrade()).isEqualTo("E"));
        // 자산 자체는 음수를 그대로 보여준다 — 판정만 0 으로 본다.
        assertThat(p.getFirst().estimatedAsset()).isNegative();
    }

    @Test
    void 곡선이_없으면_추정하지_않는다() {
        // 없는 것을 지어내지 않는다. 화면은 「아직 예측이 없습니다」를 보여준다.
        assertThat(CreditGradeProjection.project(List.of(), 원("100"), 원("100"), 구간)).isEmpty();
        assertThat(CreditGradeProjection.project(null, 원("100"), 원("100"), 구간)).isEmpty();
    }

    @Test
    void 등급_판정은_구간_하한을_포함한다() {
        // 정확히 5,000만이면 C 다. 초과가 아니라 이상이다.
        assertThat(CreditGradeProjection.gradeOf(원("50000000"), 구간)).isEqualTo("C");
        assertThat(CreditGradeProjection.gradeOf(원("49999999"), 구간)).isEqualTo("D");
        assertThat(CreditGradeProjection.gradeOf(원("100000000"), 구간)).isEqualTo("A");
        assertThat(CreditGradeProjection.gradeOf(BigDecimal.ZERO, 구간)).isEqualTo("E");
    }
}
