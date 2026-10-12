package com.jangoyebo.forecast.engine;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.common.domain.RiskLevel;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.DailyPoint;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.PlannedItem;
import com.jangoyebo.forecast.engine.SafetyLineDetector.SafetyLineSegment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 안전선 하회 파생 (P1-08). Docker 불필요 — 곡선 점 목록만 받는 순수 함수다.
 */
class SafetyLineDetectorTests {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final BigDecimal LINE = new BigDecimal("300");

    @Test
    void 안전선_아래_연속구간을_하나로_묶는다() {
        List<DailyPoint> window = curve(500, 250, 200, 280, 400, 600);

        List<SafetyLineSegment> below = SafetyLineDetector.alertable(window, LINE);

        assertThat(below).hasSize(1);
        SafetyLineSegment s = below.get(0);
        assertThat(s.firstBelowDate()).isEqualTo(LocalDate.of(2026, 9, 2));
        assertThat(s.lastBelowDate()).isEqualTo(LocalDate.of(2026, 9, 4));
        assertThat(s.durationDays()).isEqualTo(3);
        assertThat(s.lowestDate()).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(s.lowestBalance()).isEqualByComparingTo("200");
        assertThat(s.shortfall()).isEqualByComparingTo("100");
        assertThat(s.expectedRecoveryDate()).isEqualTo(LocalDate.of(2026, 9, 5));
        assertThat(s.containsGap()).isFalse();
    }

    @Test
    void 떨어진_구간은_따로_센다() {
        List<DailyPoint> window = curve(250, 400, 200, 100, 500);

        List<SafetyLineSegment> below = SafetyLineDetector.alertable(window, LINE);

        assertThat(below).hasSize(2);
        assertThat(below.get(0).firstBelowDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(below.get(0).durationDays()).isEqualTo(1);
        assertThat(below.get(1).firstBelowDate()).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(below.get(1).durationDays()).isEqualTo(2);
    }

    @Test
    void 안전선이_없으면_하회를_판정하지_않는다() {
        // 미설정은 오류가 아니라 정상 상태다. 비교선이 없는데 경고를 만들면 근거 없는 경보다.
        assertThat(SafetyLineDetector.segments(curve(10, 20, 30), null)).isEmpty();
        assertThat(SafetyLineDetector.alertable(curve(10, 20, 30), null)).isEmpty();
    }

    @Test
    void 안전선이_영이면_공백과_같은_선이라_내놓지_않는다() {
        // 0 미만은 CashGapDetector 가 가져간다. 여기서 낼 것이 남지 않는다.
        assertThat(SafetyLineDetector.segments(curve(-10, 50), BigDecimal.ZERO)).isEmpty();
    }

    @Test
    void 창_끝까지_하회면_회복일이_없다() {
        // 「30일 안에 회복하지 않는다」와 「31일째에 회복한다」를 구분할 수 없다.
        List<DailyPoint> window = curve(500, 200, 150);

        SafetyLineSegment s = SafetyLineDetector.alertable(window, LINE).get(0);

        assertThat(s.expectedRecoveryDate()).isNull();
        assertThat(s.durationDays()).isEqualTo(2);
    }

    @Test
    void 공백이_낀_구간은_알림에서_빠진다() {
        // 같은 골짜기에 두 알림을 띄우면 급한 쪽(CASH_GAP)이 묻힌다.
        List<DailyPoint> window = curve(500, 200, -50, 100, 400);

        assertThat(SafetyLineDetector.alertable(window, LINE)).isEmpty();
    }

    @Test
    void 공백이_껴도_화면용_구간에는_남는다() {
        // current 블록은 「언제부터 안전선 아래인지」를 말해야 한다 — 알림과 쓰임이 다르다.
        List<DailyPoint> window = curve(500, 200, -50, 100, 400);

        List<SafetyLineSegment> all = SafetyLineDetector.segments(window, LINE);

        assertThat(all).hasSize(1);
        assertThat(all.get(0).firstBelowDate()).isEqualTo(LocalDate.of(2026, 9, 2));
        assertThat(all.get(0).containsGap()).isTrue();
        assertThat(all.get(0).riskLevel()).isEqualTo(RiskLevel.GAP);
    }

    @Test
    void 공백이_낀_구간만_빠지고_다른_구간은_남는다() {
        List<DailyPoint> window = curve(500, -50, 400, 200, 500);

        List<SafetyLineSegment> alertable = SafetyLineDetector.alertable(window, LINE);

        assertThat(SafetyLineDetector.segments(window, LINE)).hasSize(2);
        assertThat(alertable).hasSize(1);
        assertThat(alertable.get(0).firstBelowDate()).isEqualTo(LocalDate.of(2026, 9, 4));
    }

    @Test
    void 등급은_구간_최저점으로_매긴다() {
        // 2.6 — 안전선의 25% 미만이면 DANGER 다.
        assertThat(SafetyLineDetector.alertable(curve(500, 200), LINE).get(0).riskLevel())
                .isEqualTo(RiskLevel.CAUTION);
        assertThat(SafetyLineDetector.alertable(curve(500, 200, 70), LINE).get(0).riskLevel())
                .isEqualTo(RiskLevel.DANGER);
    }

    @Test
    void 하한이_더_깊으면_범위_상단이_된다() {
        // 문구는 예상~하한 범위로 말한다. 판정은 예상으로만 한다.
        List<DailyPoint> window = List.of(
                point(START, "500", "500"),
                point(START.plusDays(1), "200", "120"));

        SafetyLineSegment s = SafetyLineDetector.alertable(window, LINE).get(0);

        assertThat(s.shortfall()).isEqualByComparingTo("100");
        assertThat(s.worstShortfall()).isEqualByComparingTo("180");
    }

    @Test
    void 하한이_안전선_위여도_범위가_뒤집히지_않는다() {
        List<DailyPoint> window = List.of(
                point(START, "500", "500"),
                point(START.plusDays(1), "290", "350"));

        SafetyLineSegment s = SafetyLineDetector.alertable(window, LINE).get(0);

        assertThat(s.worstShortfall()).isGreaterThanOrEqualTo(s.shortfall());
    }

    @Test
    void 동률이면_빠른_날을_최저로_남긴다() {
        // 사용자가 먼저 대응해야 하는 날이다.
        List<DailyPoint> window = curve(500, 200, 200, 400);

        assertThat(SafetyLineDetector.alertable(window, LINE).get(0).lowestDate())
                .isEqualTo(LocalDate.of(2026, 9, 2));
    }

    @Test
    void 안전선과_같은_금액은_하회가_아니다() {
        // 2.6 은 SAFE 를 「안전선 이상」으로 정의했다.
        assertThat(SafetyLineDetector.alertable(curve(300, 300), LINE)).isEmpty();
        assertThat(SafetyLineDetector.alertable(curve(300, 299), LINE)).hasSize(1);
    }

    @Test
    void 같은_구간은_같은_키를_낸다() {
        // 곡선을 다시 계산해도 최초 하회일이 같으면 같은 알림에 모인다.
        SafetyLineSegment s = SafetyLineDetector.alertable(curve(500, 200, 250), LINE).get(0);

        assertThat(SafetyLineDetector.dedupKey(s)).isEqualTo("safety-line:2026-09-02");
    }

    @Test
    void 원인은_구간_직전_출금부터_본다() {
        // 급여·임대료가 하루 이틀 앞서 빠져 구간 시작 전에 걸린다.
        List<DailyPoint> window = curve(500, 200, 250, 400);
        SafetyLineSegment s = SafetyLineDetector.alertable(window, LINE).get(0);

        List<CashGapDetector.Cause> causes = SafetyLineDetector.causes(s, List.of(
                withdrawal(1L, START, "300"),
                deposit(2L, START, "10")));

        assertThat(causes).extracting(CashGapDetector.Cause::plannedTransactionId).contains(1L);
    }

    // --- 헬퍼 -----------------------------------------------------------------

    private static List<DailyPoint> curve(long... expected) {
        List<DailyPoint> points = new ArrayList<>();
        for (int i = 0; i < expected.length; i++) {
            String value = String.valueOf(expected[i]);
            points.add(point(START.plusDays(i), value, value));
        }
        return points;
    }

    private static DailyPoint point(LocalDate date, String expected, String min) {
        BigDecimal e = new BigDecimal(expected);
        return new DailyPoint(date, new BigDecimal(min), e, e.max(new BigDecimal(min)),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE);
    }

    private static PlannedItem withdrawal(long id, LocalDate date, String amount) {
        BigDecimal value = new BigDecimal(amount);
        return new PlannedItem(id, date, FlowType.WITHDRAWAL, value, value, value, BigDecimal.ONE);
    }

    private static PlannedItem deposit(long id, LocalDate date, String amount) {
        BigDecimal value = new BigDecimal(amount);
        return new PlannedItem(id, date, FlowType.DEPOSIT, value, value, value, BigDecimal.ONE);
    }
}
