package com.jangoyebo.forecast.engine;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.DailyPoint;
import com.jangoyebo.forecast.engine.BalanceCurveCalculator.PlannedItem;
import com.jangoyebo.forecast.engine.CashGapDetector.CashGapSegment;
import com.jangoyebo.forecast.engine.CashGapDetector.Cause;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정산 공백 파생 (P0-08). Docker 불필요 — 곡선 점 목록만 받는 순수 함수다.
 */
class CashGapDetectorTests {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);

    @Test
    void 음수_연속구간을_하나로_묶는다() {
        List<DailyPoint> window = curve(100, -50, -80, -30, 40, 90);

        List<CashGapSegment> gaps = CashGapDetector.detect(window);

        assertThat(gaps).hasSize(1);
        CashGapSegment gap = gaps.get(0);
        assertThat(gap.firstShortfallDate()).isEqualTo(LocalDate.of(2026, 9, 2));
        assertThat(gap.lastShortfallDate()).isEqualTo(LocalDate.of(2026, 9, 4));
        assertThat(gap.durationDays()).isEqualTo(3);
        assertThat(gap.maxShortfallAmount()).isEqualByComparingTo("80");
        assertThat(gap.maxShortfallDate()).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(gap.expectedRecoveryDate()).isEqualTo(LocalDate.of(2026, 9, 5));
    }

    @Test
    void 떨어진_구간은_따로_센다() {
        List<DailyPoint> window = curve(-10, 20, -30, -40, 50);

        List<CashGapSegment> gaps = CashGapDetector.detect(window);

        assertThat(gaps).hasSize(2);
        assertThat(gaps.get(0).firstShortfallDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(gaps.get(0).durationDays()).isEqualTo(1);
        assertThat(gaps.get(1).firstShortfallDate()).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(gaps.get(1).durationDays()).isEqualTo(2);
    }

    @Test
    void 창_끝까지_음수면_회복일이_없다() {
        // 「30일 안에 회복하지 않는다」와 「31일째에 회복한다」를 구분할 수 없다.
        List<DailyPoint> window = curve(100, -20, -30);

        CashGapSegment gap = CashGapDetector.detect(window).get(0);

        assertThat(gap.expectedRecoveryDate()).isNull();
        assertThat(gap.durationDays()).isEqualTo(2);
    }

    @Test
    void 공백이_없으면_빈_목록이다() {
        assertThat(CashGapDetector.detect(curve(10, 20, 30))).isEmpty();
    }

    @Test
    void 최대_부족일은_동률이면_빠른_날이다() {
        List<DailyPoint> window = curve(-50, -50, -10);

        CashGapSegment gap = CashGapDetector.detect(window).get(0);

        assertThat(gap.maxShortfallDate()).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    void 하한_기준_부족액을_따로_들고_있다() {
        // 판정은 expected 로 하고, 알림 문구의 범위 상단에만 하한을 쓴다.
        List<DailyPoint> window = new ArrayList<>();
        window.add(point(START, "-420000", "-800000"));

        CashGapSegment gap = CashGapDetector.detect(window).get(0);

        assertThat(gap.maxShortfallAmount()).isEqualByComparingTo("420000");
        assertThat(gap.worstCaseShortfall()).isEqualByComparingTo("800000");
    }

    @Test
    void 하한이_음수여도_예상이_양수면_공백이_아니다() {
        // balanceMin 으로 판정하면 α=1 을 쓰는 것과 같아 모델 채택 근거가 사라진다.
        List<DailyPoint> window = new ArrayList<>();
        window.add(point(START, "100000", "-500000"));

        assertThat(CashGapDetector.detect(window)).isEmpty();
    }

    @Test
    void 원인은_큰_출금부터_최대_세건이다() {
        CashGapSegment gap = CashGapDetector.detect(curve(100, -50, -80, 40)).get(0);
        List<PlannedItem> planned = List.of(
                withdrawal(1L, LocalDate.of(2026, 9, 2), "3000000"),
                withdrawal(2L, LocalDate.of(2026, 9, 3), "500000"),
                withdrawal(3L, LocalDate.of(2026, 9, 2), "1000000"),
                withdrawal(4L, LocalDate.of(2026, 9, 2), "100000"),
                deposit(5L, LocalDate.of(2026, 9, 1), "900000"));

        List<Cause> causes = CashGapDetector.causes(gap, planned);

        assertThat(causes).hasSize(3);
        assertThat(causes).extracting(Cause::plannedTransactionId).containsExactly(1L, 3L, 2L);
    }

    @Test
    void 구간_직전의_출금도_원인으로_본다() {
        // 급여·임대료는 공백이 시작되기 하루 이틀 앞서 빠진다.
        CashGapSegment gap = CashGapDetector.detect(curve(100, 100, -50, 40)).get(0);
        List<PlannedItem> planned = List.of(
                withdrawal(1L, LocalDate.of(2026, 9, 1), "3000000"),
                deposit(2L, LocalDate.of(2026, 9, 2), "900000"));

        List<Cause> causes = CashGapDetector.causes(gap, planned);

        assertThat(causes).extracting(Cause::plannedTransactionId).contains(1L);
    }

    @Test
    void 예정_입금이_없으면_그_사실을_원인에_덧붙인다() {
        // 대상 매장의 공백은 큰 지출보다 「연휴로 정산이 멈춘 것」에서 온다.
        CashGapSegment gap = CashGapDetector.detect(curve(100, -50, 40)).get(0);
        List<PlannedItem> planned = List.of(withdrawal(1L, LocalDate.of(2026, 9, 2), "3000000"));

        List<Cause> causes = CashGapDetector.causes(gap, planned);

        assertThat(causes).hasSize(2);
        Cause drought = causes.get(causes.size() - 1);
        assertThat(drought.isDepositDrought()).isTrue();
        assertThat(drought.amount()).isNull();
        assertThat(drought.date()).isEqualTo(gap.firstShortfallDate());
    }

    @Test
    void 예정_입금이_있으면_덧붙이지_않는다() {
        CashGapSegment gap = CashGapDetector.detect(curve(100, -50, 40)).get(0);
        List<PlannedItem> planned = List.of(
                withdrawal(1L, LocalDate.of(2026, 9, 2), "3000000"),
                deposit(2L, LocalDate.of(2026, 9, 2), "10000"));

        List<Cause> causes = CashGapDetector.causes(gap, planned);

        assertThat(causes).noneMatch(Cause::isDepositDrought);
    }

    @Test
    void 중복키는_최초_부족일이다() {
        CashGapSegment gap = CashGapDetector.detect(curve(100, -50, 40)).get(0);

        assertThat(CashGapDetector.dedupKey(gap)).isEqualTo("cash-gap:2026-09-02");
    }

    // --- 헬퍼 -----------------------------------------------------------------

    /** 예상 잔액만 지정한 곡선. 하한은 같은 값으로 둔다. */
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
