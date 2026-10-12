package com.jangoyebo.forecast.pattern;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.core.TransactionHistory.HistoricalTransaction;
import com.jangoyebo.forecast.pattern.RecurringPatternDetector.DetectedPattern;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 반복 패턴 탐지 (P0-03).
 *
 * <p><b>Docker 가 필요 없다.</b> 탐지기가 순수 함수라 DB 없이 돈다 — 아키텍처 §6 이
 * 계산 엔진을 순수 함수로 유지하기로 한 이유가 이것이다. CI 범위 B 에서도 돈다.
 *
 * <p>기준일을 인자로 받으므로 <b>날짜가 흘러도 결과가 바뀌지 않는다.</b>
 */
class RecurringPatternDetectorTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 20);

    @Test
    void detectsMonthlyRent() {
        List<HistoricalTransaction> history = List.of(
                tx(1, "월세 이체", FlowType.WITHDRAWAL, "1200000", LocalDate.of(2026, 6, 5)),
                tx(2, "월세 이체", FlowType.WITHDRAWAL, "1200000", LocalDate.of(2026, 7, 5)),
                tx(3, "월세 이체", FlowType.WITHDRAWAL, "1200000", LocalDate.of(2026, 8, 5)),
                tx(4, "월세 이체", FlowType.WITHDRAWAL, "1200000", LocalDate.of(2026, 9, 5)));

        List<DetectedPattern> found = RecurringPatternDetector.detect(history, TODAY);

        assertThat(found).hasSize(1);
        DetectedPattern p = found.get(0);
        assertThat(p.cadenceType()).isEqualTo(CadenceType.MONTHLY);
        assertThat(p.dayOfMonth()).isEqualTo(5);
        assertThat(p.nextExpectedDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        // 금액이 고정이고 표본이 4건이라 신뢰할 만하다.
        assertThat(ConfidenceBand.of(p.confidence())).isIn(ConfidenceBand.HIGH, ConfidenceBand.MEDIUM);
    }

    /**
     * <b>월말 반복의 닻이 2월에 주저앉지 않는다</b> (S15P21C105-187).
     *
     * <p>마지막 회차 1/30 에서 {@code plusMonths} 만 하면 2/28 로 잘리고 거기서 다시 세기
     * 때문에 3월도 28일이 된다 — 한 번 내려간 일자가 영영 안 돌아온다. 탐지가 저장하는
     * <b>첫 닻</b>이라 여기서 잘리면 생성기를 고쳐도 첫 회차가 이미 틀려 있다.
     */
    @Test
    void 월말_반복의_닻은_2월을_지나도_기준일을_지킨다() {
        List<HistoricalTransaction> history = List.of(
                tx(1, "임차료", FlowType.WITHDRAWAL, "900000", LocalDate.of(2025, 11, 30)),
                tx(2, "임차료", FlowType.WITHDRAWAL, "900000", LocalDate.of(2025, 12, 30)),
                tx(3, "임차료", FlowType.WITHDRAWAL, "900000", LocalDate.of(2026, 1, 30)));

        List<DetectedPattern> found =
                RecurringPatternDetector.detect(history, LocalDate.of(2026, 3, 10));

        assertThat(found).hasSize(1);
        DetectedPattern p = found.get(0);
        assertThat(p.cadenceType()).isEqualTo(CadenceType.MONTHLY);
        assertThat(p.dayOfMonth()).isEqualTo(30);
        assertThat(p.nextExpectedDate())
                .as("2월에 28일로 잘렸어도 3월엔 30일로 돌아와야 한다")
                .isEqualTo(LocalDate.of(2026, 3, 30));
    }

    /** 일자가 며칠 흔들려도 같은 패턴이다 — 주말·공휴일 때문에 실제로 그렇다. */
    @Test
    void toleratesSmallDayOfMonthDrift() {
        List<HistoricalTransaction> history = List.of(
                tx(1, "급여", FlowType.WITHDRAWAL, "3000000", LocalDate.of(2026, 6, 25)),
                tx(2, "급여", FlowType.WITHDRAWAL, "3000000", LocalDate.of(2026, 7, 24)),
                tx(3, "급여", FlowType.WITHDRAWAL, "3000000", LocalDate.of(2026, 8, 26)));

        List<DetectedPattern> found = RecurringPatternDetector.detect(history, TODAY);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).cadenceType()).isEqualTo(CadenceType.MONTHLY);
    }

    @Test
    void detectsWeeklySettlement() {
        List<HistoricalTransaction> history = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 8, 4);   // 화요일
        for (int i = 0; i < 6; i++) {
            history.add(tx(i + 1, "우아한형제들", FlowType.DEPOSIT, "880000", d.plusWeeks(i)));
        }

        List<DetectedPattern> found = RecurringPatternDetector.detect(history, TODAY);

        assertThat(found).hasSize(1);
        DetectedPattern p = found.get(0);
        assertThat(p.cadenceType()).isEqualTo(CadenceType.WEEKLY);
        assertThat(p.dayOfWeek()).isEqualTo(DayOfWeek.TUESDAY.getValue());
        assertThat(p.nextExpectedDate().getDayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
        assertThat(p.nextExpectedDate()).isAfterOrEqualTo(TODAY);
    }

    /**
     * 카드 정산 — V5 의 {@code BUSINESS_DAILY} 가 실제로 쓰이는 자리다.
     *
     * <p>{@code INTERVAL: 1} 로 잡히면 주말에도 입금을 예측하고, 공휴일 보정에서 토·일
     * 예정분이 월요일로 몰려 한 날에 3건이 된다(설계노트 G-2).
     */
    @Test
    void detectsBusinessDailyCardSettlement() {
        List<HistoricalTransaction> history = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 8, 3);   // 월요일
        int id = 1;
        while (d.isBefore(LocalDate.of(2026, 9, 1))) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                history.add(tx(id++, "신한카드", FlowType.DEPOSIT, "310000", d));
            }
            d = d.plusDays(1);
        }

        List<DetectedPattern> found = RecurringPatternDetector.detect(history, TODAY);

        assertThat(found).hasSize(1);
        DetectedPattern p = found.get(0);
        assertThat(p.cadenceType()).isEqualTo(CadenceType.BUSINESS_DAILY);
        // 다음 예상일이 주말로 나오면 안 된다.
        assertThat(p.nextExpectedDate().getDayOfWeek())
                .isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    }

    /** 주말에도 있으면 영업일 반복이 아니다 — 그 지점이 두 주기를 가른다. */
    @Test
    void doesNotCallEveryDayBusinessDaily() {
        List<HistoricalTransaction> history = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 8, 3);
        for (int i = 0; i < 20; i++) {
            history.add(tx(i + 1, "일일정산", FlowType.DEPOSIT, "100000", d.plusDays(i)));
        }

        List<DetectedPattern> found = RecurringPatternDetector.detect(history, TODAY);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).cadenceType()).isEqualTo(CadenceType.INTERVAL);
        assertThat(found.get(0).intervalValue()).isEqualTo(1);
    }

    /** 격주는 WEEKLY 가 아니라 INTERVAL 이다 — 같은 요일이어도 간격이 다르다. */
    @Test
    void classifiesBiweeklyAsInterval() {
        List<HistoricalTransaction> history = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 7, 7);
        for (int i = 0; i < 5; i++) {
            history.add(tx(i + 1, "격주 정산", FlowType.DEPOSIT, "500000", d.plusWeeks(i * 2L)));
        }

        List<DetectedPattern> found = RecurringPatternDetector.detect(history, TODAY);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).cadenceType()).isEqualTo(CadenceType.INTERVAL);
        assertThat(found.get(0).intervalValue()).isEqualTo(14);
    }

    /**
     * 적요에 섞인 회차·날짜를 지우고 묶는다.
     *
     * <p>원문으로 묶으면 각 1건이 되어 <b>아무것도 탐지되지 않는다.</b>
     */
    @Test
    void groupsDespiteVaryingDigitsInDescription() {
        List<HistoricalTransaction> history = List.of(
                tx(1, "카드정산 0605", FlowType.DEPOSIT, "300000", LocalDate.of(2026, 6, 5)),
                tx(2, "카드정산 0705", FlowType.DEPOSIT, "310000", LocalDate.of(2026, 7, 5)),
                tx(3, "카드정산 0805", FlowType.DEPOSIT, "295000", LocalDate.of(2026, 8, 5)));

        List<DetectedPattern> found = RecurringPatternDetector.detect(history, TODAY);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).cadenceType()).isEqualTo(CadenceType.MONTHLY);
        // 이름은 정규화한 키가 아니라 적요 원문이다.
        assertThat(found.get(0).name()).isEqualTo("카드정산 0805");
    }

    @Test
    void ignoresTooFewSamples() {
        List<HistoricalTransaction> history = List.of(
                tx(1, "일회성", FlowType.WITHDRAWAL, "50000", LocalDate.of(2026, 8, 1)),
                tx(2, "일회성", FlowType.WITHDRAWAL, "50000", LocalDate.of(2026, 9, 1)));

        assertThat(RecurringPatternDetector.detect(history, TODAY)).isEmpty();
    }

    /** 간격이 제멋대로면 후보로 올리지 않는다. 틀린 예측이 없는 것보다 나쁘다. */
    @Test
    void ignoresIrregularIntervals() {
        List<HistoricalTransaction> history = List.of(
                tx(1, "잡비", FlowType.WITHDRAWAL, "30000", LocalDate.of(2026, 6, 1)),
                tx(2, "잡비", FlowType.WITHDRAWAL, "30000", LocalDate.of(2026, 6, 3)),
                tx(3, "잡비", FlowType.WITHDRAWAL, "30000", LocalDate.of(2026, 7, 20)),
                tx(4, "잡비", FlowType.WITHDRAWAL, "30000", LocalDate.of(2026, 8, 30)));

        assertThat(RecurringPatternDetector.detect(history, TODAY)).isEmpty();
    }

    /** 같은 날 여러 건은 분할 결제지 반복이 아니다. */
    @Test
    void ignoresSameDayRepetition() {
        LocalDate day = LocalDate.of(2026, 8, 10);
        List<HistoricalTransaction> history = List.of(
                tx(1, "분할", FlowType.WITHDRAWAL, "10000", day),
                tx(2, "분할", FlowType.WITHDRAWAL, "10000", day),
                tx(3, "분할", FlowType.WITHDRAWAL, "10000", day));

        assertThat(RecurringPatternDetector.detect(history, TODAY)).isEmpty();
    }

    /** 입금과 출금은 적요가 같아도 다른 패턴이다 — 계좌 간 이체가 그렇다. */
    @Test
    void separatesByFlowType() {
        List<HistoricalTransaction> history = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 6, 10);
        for (int i = 0; i < 4; i++) {
            history.add(tx(i * 2 + 1, "이체", FlowType.DEPOSIT, "100000", d.plusMonths(i)));
            history.add(tx(i * 2 + 2, "이체", FlowType.WITHDRAWAL, "100000", d.plusMonths(i)));
        }

        List<DetectedPattern> found = RecurringPatternDetector.detect(history, TODAY);

        assertThat(found).hasSize(2);
        assertThat(found).extracting(DetectedPattern::flowType)
                .containsExactlyInAnyOrder(FlowType.DEPOSIT, FlowType.WITHDRAWAL);
    }

    /** 마지막 거래가 오래돼도 다음 예상일은 오늘 이후여야 한다. */
    @Test
    void pushesNextDateBeyondToday() {
        List<HistoricalTransaction> history = List.of(
                tx(1, "오래된 월세", FlowType.WITHDRAWAL, "500000", LocalDate.of(2026, 1, 10)),
                tx(2, "오래된 월세", FlowType.WITHDRAWAL, "500000", LocalDate.of(2026, 2, 10)),
                tx(3, "오래된 월세", FlowType.WITHDRAWAL, "500000", LocalDate.of(2026, 3, 10)));

        List<DetectedPattern> found = RecurringPatternDetector.detect(history, TODAY);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).nextExpectedDate()).isAfterOrEqualTo(TODAY);
    }

    /** 금액이 크게 튀면 신뢰도가 떨어진다 — 셋을 곱하는 이유다. */
    @Test
    void lowersConfidenceWhenAmountsSwingWidely() {
        List<HistoricalTransaction> stable = List.of(
                tx(1, "안정", FlowType.DEPOSIT, "1000000", LocalDate.of(2026, 6, 10)),
                tx(2, "안정", FlowType.DEPOSIT, "1000000", LocalDate.of(2026, 7, 10)),
                tx(3, "안정", FlowType.DEPOSIT, "1000000", LocalDate.of(2026, 8, 10)));
        List<HistoricalTransaction> volatile_ = List.of(
                tx(4, "변동", FlowType.DEPOSIT, "100000", LocalDate.of(2026, 6, 10)),
                tx(5, "변동", FlowType.DEPOSIT, "1000000", LocalDate.of(2026, 7, 10)),
                tx(6, "변동", FlowType.DEPOSIT, "3000000", LocalDate.of(2026, 8, 10)));

        BigDecimal stableScore = RecurringPatternDetector.detect(stable, TODAY).get(0).confidence();
        BigDecimal volatileScore = RecurringPatternDetector.detect(volatile_, TODAY).get(0).confidence();

        assertThat(volatileScore).isLessThan(stableScore);
    }

    @Test
    void mapsConfidenceBands() {
        assertThat(ConfidenceBand.of(new BigDecimal("0.95"))).isEqualTo(ConfidenceBand.HIGH);
        assertThat(ConfidenceBand.of(new BigDecimal("0.70"))).isEqualTo(ConfidenceBand.MEDIUM);
        assertThat(ConfidenceBand.of(new BigDecimal("0.45"))).isEqualTo(ConfidenceBand.LOW);
        assertThat(ConfidenceBand.of(new BigDecimal("0.10"))).isEqualTo(ConfidenceBand.UNPREDICTABLE);
        assertThat(ConfidenceBand.of(null)).isEqualTo(ConfidenceBand.UNPREDICTABLE);
    }

    /**
     * <b>급여는 휴일이면 앞당겨 준다</b> — 그걸 과거 회차에서 읽어낸다.
     *
     * <p>2026-05-10 이 일요일이라 그 달만 05-08(금)에 나갔다. 방향을 안 읽으면 05-11(월)로
     * 봤을 것이고, 앞으로의 투영이 전부 반대쪽으로 밀린다. 실측에서 급여 120만원이 나흘
     * 늦게 찍히면서 공백 나흘이 통째로 사라졌다.
     */
    @Test
    void 급여의_보정_방향을_과거에서_읽는다() {
        List<HistoricalTransaction> history = new ArrayList<>();
        LocalDate[] paid = {
                LocalDate.of(2026, 4, 10), LocalDate.of(2026, 5, 8),   // 5/10 이 일요일 → 앞으로
                LocalDate.of(2026, 6, 10), LocalDate.of(2026, 7, 10),
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 9, 10),
        };
        for (int i = 0; i < paid.length; i++) {
            history.add(tx(i + 1, "박정호", FlowType.WITHDRAWAL, "1200000", paid[i]));
        }

        DetectedPattern p = RecurringPatternDetector.detect(history, TODAY).get(0);

        assertThat(p.cadenceType()).isEqualTo(CadenceType.MONTHLY);
        assertThat(p.dayOfMonth()).isEqualTo(10);
        assertThat(p.holidayRule()).isEqualTo(HolidayRule.PREVIOUS_BUSINESS_DAY);
    }

    /**
     * 임차료는 반대다 — 휴일이면 뒤로 밀린다. <b>같은 사업장에서 방향이 갈린다.</b>
     *
     * <p>기준일도 같이 바로잡는다. 첫 회차 04-06 은 <b>이미 보정된 날짜</b>라 그대로 쓰면
     * 기준일이 6 이 되는데 실제 계약일은 5 다. 6 으로 두면 이후 투영이 하루씩 밀린다.
     */
    @Test
    void 임차료는_뒤로_밀리고_기준일도_바로잡는다() {
        List<HistoricalTransaction> history = new ArrayList<>();
        LocalDate[] paid = {
                LocalDate.of(2026, 4, 6),   // 4/5 일요일 → 뒤로
                LocalDate.of(2026, 5, 6),   // 5/5 어린이날 → 뒤로
                LocalDate.of(2026, 6, 5),   // 금요일 그대로
                LocalDate.of(2026, 7, 6),   // 7/5 일요일 → 뒤로
                LocalDate.of(2026, 8, 5),   // 수요일 그대로
                LocalDate.of(2026, 9, 7),   // 9/5 토요일 → 뒤로
        };
        for (int i = 0; i < paid.length; i++) {
            history.add(tx(i + 1, "김영자", FlowType.WITHDRAWAL, "900000", paid[i]));
        }

        DetectedPattern p = RecurringPatternDetector.detect(history, TODAY).get(0);

        assertThat(p.dayOfMonth()).as("첫 회차가 이미 보정된 날짜라 6 으로 잡히면 안 된다").isEqualTo(5);
        assertThat(p.holidayRule()).isEqualTo(HolidayRule.NEXT_BUSINESS_DAY);
    }

    /**
     * <b>증거가 없으면 바꾸지 않는다.</b> 기준일이 한 번도 휴일에 걸리지 않았으면 방향을
     * 알 길이 없다 — 모르는 것을 아는 척하는 대신 기존 기본값을 둔다.
     */
    @Test
    void 휴일에_한번도_안_걸리면_기본값을_둔다() {
        List<HistoricalTransaction> history = new ArrayList<>();
        LocalDate[] paid = {
                LocalDate.of(2026, 4, 20), LocalDate.of(2026, 5, 20),
                LocalDate.of(2026, 6, 22), LocalDate.of(2026, 7, 20),
                LocalDate.of(2026, 8, 20), LocalDate.of(2026, 9, 21),
        };
        for (int i = 0; i < paid.length; i++) {
            history.add(tx(i + 1, "케이티", FlowType.WITHDRAWAL, "70000", paid[i]));
        }

        DetectedPattern p = RecurringPatternDetector.detect(history, TODAY).get(0);

        assertThat(p.holidayRule()).isEqualTo(HolidayRule.NEXT_BUSINESS_DAY);
    }

    private static HistoricalTransaction tx(long id, String description, FlowType flow,
                                            String amount, LocalDate date) {
        return new HistoricalTransaction(id, 21L, null, date, flow, new BigDecimal(amount), description);
    }

    /**
     * 카드 정산은 <b>요일마다 금액이 다르다</b> — 주말·공휴일 매출이 다음 영업일로 몰린다.
     *
     * <p>실측(하노식당 · 신한카드 124건)에서 월요일 중앙값 385,750원, 금요일 118,600원으로
     * <b>3배</b> 차이였다. 범위 하나로 뭉개면 월요일은 모자라고 화~금은 과대하다
     * (설계노트 G-2 「금액 축」).
     */
    @Test
    void splitsBusinessDailyAmountsByWeekday() {
        List<HistoricalTransaction> history = new ArrayList<>();
        int id = 1;
        // 8주치 — 요일당 8건이라 MIN_WEEKDAY_SAMPLES(6) 를 넘는다.
        for (LocalDate d = LocalDate.of(2026, 7, 6); d.isBefore(LocalDate.of(2026, 8, 31));
                d = d.plusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            String amount = d.getDayOfWeek() == DayOfWeek.MONDAY ? "390000" : "130000";
            history.add(tx(id++, "신한카드", FlowType.DEPOSIT, amount, d));
        }

        DetectedPattern p = RecurringPatternDetector.detect(history, TODAY).get(0);

        assertThat(p.cadenceType()).isEqualTo(CadenceType.BUSINESS_DAILY);
        assertThat(p.amountByWeekday()).isNotNull().containsOnlyKeys(1, 2, 3, 4, 5);
        assertThat(p.amountByWeekday().get(1).mid()).isEqualByComparingTo("390000");
        assertThat(p.amountByWeekday().get(5).mid()).isEqualByComparingTo("130000");
    }

    /**
     * <b>이상치 한 건이 폭을 지배하면 안 된다.</b> 최소·최대가 아니라 q10/q90 을 쓰는 이유다.
     *
     * <p>실측에서 화요일 정산 24건 중 582,100원이 한 건 있었는데, 최대로 잡으면 그 요일 폭이
     * 47만원이 된다. 곡선 밴드가 일별 폭의 30일 누적이라 그 차이가 그대로 쌓인다.
     */
    @Test
    void ignoresOutlierWhenBuildingWeekdayRange() {
        List<HistoricalTransaction> history = new ArrayList<>();
        int id = 1;
        for (LocalDate d = LocalDate.of(2026, 7, 6); d.isBefore(LocalDate.of(2026, 8, 31));
                d = d.plusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            // 화요일 딱 한 번만 다섯 배로 튄다.
            boolean spike = d.equals(LocalDate.of(2026, 7, 14));
            history.add(tx(id++, "신한카드", FlowType.DEPOSIT, spike ? "650000" : "130000", d));
        }

        DetectedPattern p = RecurringPatternDetector.detect(history, TODAY).get(0);

        assertThat(p.amountMax()).isEqualByComparingTo("650000");          // 전체 최대는 튄 값
        assertThat(p.amountByWeekday().get(2).hi()).isEqualByComparingTo("130000");  // q90 은 아니다
    }

    /**
     * 표본이 모자라면 <b>전체를</b> 쓰지 않는다.
     *
     * <p>3~4건으로 뽑은 q10/q90 은 사실상 최소·최대라 폭을 좁히려는 목적을 잃는다. 한 요일만
     * 모자라도 {@code null} 인 것은 같은 패턴 안에서 기준이 갈리지 않게 하려는 것이다.
     */
    @Test
    void skipsWeekdayAmountsWhenSamplesAreThin() {
        List<HistoricalTransaction> history = new ArrayList<>();
        int id = 1;
        for (LocalDate d = LocalDate.of(2026, 8, 3); d.isBefore(LocalDate.of(2026, 8, 22));
                d = d.plusDays(1)) {                       // 3주 → 요일당 3건
            if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            history.add(tx(id++, "신한카드", FlowType.DEPOSIT, "130000", d));
        }

        DetectedPattern p = RecurringPatternDetector.detect(history, TODAY).get(0);

        assertThat(p.cadenceType()).isEqualTo(CadenceType.BUSINESS_DAILY);
        assertThat(p.amountByWeekday()).isNull();
    }

    /** 매월·매주 패턴은 요일이 하나뿐이라 나눌 것이 없다. */
    @Test
    void leavesWeekdayAmountsNullForMonthly() {
        List<HistoricalTransaction> history = List.of(
                tx(1, "월세 이체", FlowType.WITHDRAWAL, "1200000", LocalDate.of(2026, 6, 5)),
                tx(2, "월세 이체", FlowType.WITHDRAWAL, "1200000", LocalDate.of(2026, 7, 5)),
                tx(3, "월세 이체", FlowType.WITHDRAWAL, "1200000", LocalDate.of(2026, 8, 5)));

        assertThat(RecurringPatternDetector.detect(history, TODAY).get(0).amountByWeekday()).isNull();
    }

    /**
     * 요일별 금액이 있으면 <b>신뢰도도 요일별 폭으로</b> 잰다.
     *
     * <p>아래 데이터는 요일 안에서는 금액이 한 푼도 안 흔들리고 요일끼리만 3배 차이 난다.
     * 통짜 min/max 로 재면 금액 안정성이 {@code 0.5 + 0.5/3 = 0.667} 이라 나머지 두 계수가
     * 만점이어도 신뢰도가 0.667 을 못 넘는다. 요일별로 재면 각 요일 폭이 1배라 1.0 이다.
     *
     * <p>그래서 <b>0.70 을 넘는다는 것 자체가</b> 통짜 범위를 안 봤다는 증거다 — 규칙성
     * 계수를 몰라도 성립한다.
     */
    @Test
    void scoresAmountStabilityPerWeekdayWhenSplit() {
        DetectedPattern p = RecurringPatternDetector.detect(
                cardSettlements(LocalDate.of(2026, 7, 6), LocalDate.of(2026, 8, 31)), TODAY).get(0);

        assertThat(p.amountByWeekday()).isNotNull();
        assertThat(p.confidence()).isGreaterThan(new BigDecimal("0.70"));
    }

    /**
     * 요일별 금액이 없으면 <b>예전 그대로</b> 통짜 범위로 잰다.
     *
     * <p>위와 금액 구조는 같고 기간만 3주라 요일당 3건뿐이다({@code MIN_WEEKDAY_SAMPLES} 미달).
     * 이때는 폴백이 걸려 0.667 천장이 그대로 살아 있어야 한다 — 요일 축이 없는 패턴의
     * 점수를 이 변경이 건드리지 않는다는 뜻이다.
     */
    @Test
    void keepsWholeRangeScoringWhenWeekdayAmountsAreThin() {
        DetectedPattern p = RecurringPatternDetector.detect(
                cardSettlements(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 22)), TODAY).get(0);

        assertThat(p.amountByWeekday()).isNull();
        assertThat(p.confidence()).isLessThan(new BigDecimal("0.70"));
    }

    /** 영업일마다 들어오는 카드 정산. 월요일만 3배고 요일 안에서는 흔들리지 않는다. */
    private static List<HistoricalTransaction> cardSettlements(LocalDate from, LocalDate until) {
        List<HistoricalTransaction> history = new ArrayList<>();
        int id = 1;
        for (LocalDate d = from; d.isBefore(until); d = d.plusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            String amount = d.getDayOfWeek() == DayOfWeek.MONDAY ? "390000" : "130000";
            history.add(tx(id++, "신한카드", FlowType.DEPOSIT, amount, d));
        }
        return history;
    }
}
