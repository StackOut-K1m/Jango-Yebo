package com.jangoyebo.forecast.pattern;

import com.jangoyebo.common.domain.BusinessCalendar;
import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.core.TransactionHistory.HistoricalTransaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 과거 거래에서 반복 규칙을 찾는다 (P0-03).
 *
 * <p><b>순수 함수다.</b> DB·HTTP·시계를 건드리지 않고 입력만으로 결과가 정해진다
 * (아키텍처 §6 — 계산 엔진은 순수 함수로 유지한다). 그래서 Docker 없이 단위 테스트가 되고,
 * 같은 입력에 같은 결과가 나온다.
 *
 * <p><b>LLM 이 아니다.</b> 적요·간격·요일만 본다(SRS P0-03). LLM 분류는 P1-01 이고 다른 축이다.
 *
 * <h2>왜 적요로 묶는가</h2>
 *
 * <p>금융망 응답에 거래처명 필드가 없다 — {@code transactionSummary}(적요) 하나뿐이다.
 * 그래서 적요가 사실상 유일한 그룹핑 근거다. 다만 적요에는 회차 번호·날짜가 섞이므로
 * ({@code "카드정산 0912"}) 숫자를 지운 뒤 비교한다.
 */
public final class RecurringPatternDetector {

    /**
     * <b>거래처가 같은가</b> — 적요에서 회차·날짜를 지운 비교용 키.
     *
     * <p>{@code "카드정산 0912"} 와 {@code "카드정산 0913"} 은 같은 패턴인데 원문으로 묶으면
     * 각각 1건이 되어 <b>아무것도 탐지되지 않는다.</b> 숫자·공백·구분자를 지우고 비교한다.
     *
     * <p><b>공개인 이유는 매처가 같은 정의를 써야 하기 때문이다</b>({@code PlannedMatcher}).
     * 「같은 거래처」 판정이 두 벌로 갈리면 <b>탐지가 한 패턴으로 묶은 거래를 매칭이 다른
     * 것으로 보거나 그 반대가 된다</b> — {@code MatchPolicy} 가 허용폭의 출처를 탐지기로
     * 못박은 것과 같은 이유다.
     */
    public static String merchantKey(String description) {
        if (description == null) {
            return "";
        }
        return description.toLowerCase().replaceAll("[0-9\\s\\-_.,()/]+", "");
    }

    // --- 조정 가능 지점 -------------------------------------------------------

    /**
     * 후보로 올리는 최소 표본 수.
     *
     * <p>2건이면 우연히 두 번 일어난 것과 구분되지 않는다. 3건이면 간격이 두 번 관측되어
     * 규칙성을 말할 수 있다. 월 단위 패턴은 3건에 3개월이 필요하다.
     */
    static final int MIN_SAMPLES = 3;

    /** 간격 편차가 이 비율을 넘으면 규칙적이라고 보지 않는다. */
    static final double MAX_INTERVAL_DEVIATION = 0.25;

    /** {@code BUSINESS_DAILY} 판정 — 관측된 영업일 중 이 비율 이상에 거래가 있어야 한다. */
    static final double BUSINESS_DAILY_COVERAGE = 0.6;

    /** 월 단위 판정 — 일자가 이 일수 안에서 흔들리는 것까지 같은 패턴으로 본다. */
    static final int MONTHLY_DAY_TOLERANCE = 2;

    /** 이 개수를 넘는 후보는 상위 신뢰도만 남긴다. 화면이 감당할 수 없다. */
    static final int MAX_CANDIDATES = 30;

    /**
     * 요일별 금액을 쓰려면 그 요일에 이만큼은 관측돼야 한다.
     *
     * <p>6주치다. 그보다 적으면 q10/q90 이 최소·최대와 같아져 의미가 없다.
     */
    static final int MIN_WEEKDAY_SAMPLES = 6;

    private RecurringPatternDetector() {
    }

    /**
     * @param today 다음 예상일 계산의 기준. 인자로 받는 이유는 순수 함수를 유지하기 위해서다 —
     *              내부에서 {@code LocalDate.now()} 를 부르면 테스트가 날짜에 따라 흔들린다
     */
    public static List<DetectedPattern> detect(List<HistoricalTransaction> transactions, LocalDate today) {
        Map<GroupKey, List<HistoricalTransaction>> groups = new LinkedHashMap<>();
        for (HistoricalTransaction tx : transactions) {
            groups.computeIfAbsent(GroupKey.of(tx), k -> new ArrayList<>()).add(tx);
        }

        List<DetectedPattern> found = new ArrayList<>();
        for (Map.Entry<GroupKey, List<HistoricalTransaction>> entry : groups.entrySet()) {
            if (entry.getValue().size() < MIN_SAMPLES) {
                continue;
            }
            DetectedPattern pattern = analyze(entry.getKey(), entry.getValue(), today);
            if (pattern != null && pattern.confidence().compareTo(BigDecimal.ZERO) > 0) {
                found.add(pattern);
            }
        }

        found.sort(Comparator.comparing(DetectedPattern::confidence).reversed());
        return found.size() > MAX_CANDIDATES ? found.subList(0, MAX_CANDIDATES) : found;
    }

    private static DetectedPattern analyze(GroupKey key, List<HistoricalTransaction> samples, LocalDate today) {
        List<LocalDate> dates = samples.stream().map(HistoricalTransaction::date).sorted().distinct().toList();
        if (dates.size() < MIN_SAMPLES) {
            // 같은 날 여러 건이면 날짜가 줄어든다 — 그건 반복이 아니라 분할 결제다.
            return null;
        }

        Cadence cadence = classify(dates);
        if (cadence == null) {
            return null;
        }

        List<BigDecimal> amounts = samples.stream().map(HistoricalTransaction::amount).sorted().toList();
        BigDecimal min = amounts.get(0);
        BigDecimal max = amounts.get(amounts.size() - 1);

        Map<Integer, WeekdayAmount> byWeekday = weekdayAmounts(cadence.type(), samples);
        BigDecimal confidence = score(dates.size(), cadence.regularity(), min, max, byWeekday);

        MonthlyFit fit = cadence.type() == CadenceType.MONTHLY ? fitMonthly(dates) : null;
        // Integer.valueOf 를 빼면 안 된다 — 삼항의 한쪽이 int 면 전체가 int 로 언박싱돼
        // MONTHLY 가 아닌 패턴에서 cadence.dayOfMonth() 가 null 일 때 NPE 로 터진다.
        Integer dayOfMonth = fit == null ? cadence.dayOfMonth() : Integer.valueOf(fit.day());
        HolidayRule holidayRule = fit == null ? HolidayRule.NEXT_BUSINESS_DAY : fit.rule();

        return new DetectedPattern(
                key.accountId(), key.categoryId(), key.flowType(),
                displayName(samples), cadence.type(), cadence.intervalValue(),
                dayOfMonth, cadence.dayOfWeek(), holidayRule,
                min, max, byWeekday,
                cadence.nextDateAfter(dates.get(dates.size() - 1), today),
                confidence,
                samples.stream().map(HistoricalTransaction::transactionId).toList());
    }

    /**
     * <b>기준일과 휴일 보정 방향을 과거 회차에서 읽어낸다</b> (MONTHLY 전용).
     *
     * <p>지금까지 {@code holidayRule} 은 언제나 {@code NEXT_BUSINESS_DAY} 였다. 그런데
     * <b>같은 사업장에서 패턴마다 방향이 다르다</b> — 자동이체는 휴일이면 밀리고 급여는
     * 앞당겨 준다. 실측(시드 「카페 한잔」):
     *
     * <pre>
     * 급여   박정호   10일   2026-05-08(금)   5/10 이 일요일 → <b>앞으로</b>
     * 임차료 김영자    5일   2026-04-06(월)   4/5 가 일요일  → <b>뒤로</b>
     *                       2026-09-07(월)   9/5 가 토요일  → 뒤로
     * </pre>
     *
     * <p>방향이 틀리면 금액이 아니라 <b>날짜</b>가 어긋난다. 위 급여 120만원이 10/12 로
     * 밀리면서 10/08~10/11 나흘의 공백이 통째로 사라졌다.
     *
     * <h2>기준일도 같이 고친다</h2>
     *
     * <p>기존 코드는 {@code dates.get(0).getDayOfMonth()} 를 기준일로 썼는데 <b>첫 회차가
     * 이미 보정돼 있으면 그 값이 틀린다.</b> 김영자의 첫 회차는 04-06 이라 기준일이 6 으로
     * 잡히지만 실제 계약일은 5 다. 그래서 (기준일 × 방향) 조합을 훑어 <b>관측을 가장 많이
     * 설명하는 쌍</b>을 고른다. 김영자는 (5, 뒤로)가 6/6 을 설명한다.
     *
     * <h2>증거가 없으면 바꾸지 않는다</h2>
     *
     * <p>기준일이 한 번도 휴일에 걸리지 않았으면 방향을 알 길이 없다. 그때는 기존 기본값
     * ({@code NEXT_BUSINESS_DAY})을 그대로 둔다 — 모르는 것을 아는 척하지 않는다.
     * 전부 설명하지 못하는 조합도 버린다. 애매한 추론보다 지금 동작이 낫다.
     */
    private static MonthlyFit fitMonthly(List<LocalDate> dates) {
        int lo = dates.stream().mapToInt(LocalDate::getDayOfMonth).min().orElse(1);
        int hi = dates.stream().mapToInt(LocalDate::getDayOfMonth).max().orElse(28);

        MonthlyFit best = null;
        // NONE 을 먼저 본다 — 한 번도 안 밀린 패턴이 억지 방향을 얻지 않게.
        HolidayRule[] rules = {HolidayRule.NONE, HolidayRule.NEXT_BUSINESS_DAY,
                HolidayRule.PREVIOUS_BUSINESS_DAY};

        // 29~31 일은 없는 달이 있어 기준일로 쓸 수 없다. 그런 패턴은 지금도 MONTHLY 로
        // 잡히지 않으므로 28 에서 자른다.
        for (int day = Math.max(1, lo - MONTHLY_DAY_TOLERANCE);
             day <= Math.min(28, hi + MONTHLY_DAY_TOLERANCE); day++) {
            for (HolidayRule rule : rules) {
                int matched = 0;
                int evidence = 0;
                for (LocalDate observed : dates) {
                    LocalDate anchorDate = observed.withDayOfMonth(day);
                    if (!BusinessCalendar.isBusinessDay(anchorDate)) {
                        evidence++;
                    }
                    if (shift(anchorDate, rule).equals(observed)) {
                        matched++;
                    }
                }
                if (best == null || matched > best.matched()) {
                    best = new MonthlyFit(day, rule, matched, evidence);
                }
            }
        }
        if (best == null || best.matched() < dates.size()) {
            return null;                       // 설명이 안 된다 — 지금 동작을 유지한다
        }
        return best.evidence() == 0
                ? new MonthlyFit(best.day(), HolidayRule.NEXT_BUSINESS_DAY, best.matched(), 0)
                : best;
    }

    /**
     * 월 회차를 <b>기준일 자리로 되돌린다</b> (S15P21C105-187).
     *
     * <p>{@code LocalDate.plusMonths} 는 없는 날을 그 달 마지막 날로 <b>자른다.</b> 1/30 이
     * 2/28 이 되는 것까지는 맞는데, 다음 회차를 그 잘린 날짜에서 다시 세기 때문에 3월도 28일이
     * 되고 <b>한 번 내려간 일자가 영영 안 돌아온다.</b> 그래서 셀 때마다 기준일로 다시 맞춘다 —
     * 2월엔 잘리되 3월엔 30일이다.
     *
     * <p>기준일이 없으면({@code null}) 지금 동작을 그대로 둔다. {@code MONTHLY} 는 탐지기가
     * 항상 채우지만, 모르는 값을 지어내는 것보다 하던 대로 두는 편이 낫다.
     */
    static LocalDate onDayOfMonth(LocalDate date, Integer dayOfMonth) {
        return dayOfMonth == null
                ? date
                : date.withDayOfMonth(Math.min(dayOfMonth, date.lengthOfMonth()));
    }

    private static LocalDate shift(LocalDate date, HolidayRule rule) {
        return switch (rule) {
            case NONE -> date;
            case NEXT_BUSINESS_DAY -> BusinessCalendar.nextBusinessDay(date);
            case PREVIOUS_BUSINESS_DAY -> BusinessCalendar.previousBusinessDay(date);
        };
    }

    /** @param evidence 기준일이 실제로 휴일이었던 회차 수. 0 이면 방향을 알 수 없다 */
    private record MonthlyFit(int day, HolidayRule rule, int matched, int evidence) {
    }

    /**
     * 요일별 금액 분위수. {@code BUSINESS_DAILY} 가 아니거나 표본이 모자라면 {@code null}.
     *
     * <p><b>요일당 {@link #MIN_WEEKDAY_SAMPLES}건을 요구한다.</b> 3~4건으로 뽑은 q10/q90 은
     * 사실상 최소·최대라 폭을 좁히려는 목적을 잃는다. 한 요일이라도 모자라면 전체를
     * {@code null} 로 둔다 — 일부만 요일별로 쓰면 같은 패턴 안에서 기준이 갈린다.
     *
     * <p>매월·매주 패턴에는 적용하지 않는다. 요일이 하나뿐이라 나눌 것이 없다.
     */
    private static Map<Integer, WeekdayAmount> weekdayAmounts(
            CadenceType cadence, List<HistoricalTransaction> samples) {
        if (cadence != CadenceType.BUSINESS_DAILY) {
            return null;
        }
        Map<Integer, List<BigDecimal>> byWeekday = new TreeMap<>();
        for (HistoricalTransaction t : samples) {
            byWeekday.computeIfAbsent(t.date().getDayOfWeek().getValue(), k -> new ArrayList<>())
                    .add(t.amount());
        }
        Map<Integer, WeekdayAmount> out = new TreeMap<>();
        for (Map.Entry<Integer, List<BigDecimal>> e : byWeekday.entrySet()) {
            List<BigDecimal> sorted = e.getValue().stream().sorted().toList();
            if (sorted.size() < MIN_WEEKDAY_SAMPLES) {
                return null;
            }
            out.put(e.getKey(), new WeekdayAmount(
                    quantile(sorted, 0.10), quantile(sorted, 0.50), quantile(sorted, 0.90)));
        }
        return out.isEmpty() ? null : out;
    }

    /** 가장 가까운 순위값. 보간하지 않는다 — 실제로 있었던 금액만 예정 거래에 싣는다. */
    private static BigDecimal quantile(List<BigDecimal> sorted, double q) {
        int i = (int) Math.round(q * (sorted.size() - 1));
        return sorted.get(Math.clamp(i, 0, sorted.size() - 1));
    }

    /**
     * 주기를 판정한다. 우선순위가 있다 — <b>좁은 규칙부터</b> 본다.
     *
     * <p>매주 화요일 입금은 {@code INTERVAL: 7} 로도 설명되지만 {@code WEEKLY} 가 더 많은
     * 정보를 담는다(요일이 화면에 나간다). 반대로 {@code INTERVAL} 을 먼저 보면
     * 모든 주간 패턴이 간격으로 뭉개진다.
     */
    private static Cadence classify(List<LocalDate> dates) {
        Cadence businessDaily = BusinessDaily.tryMatch(dates);
        if (businessDaily != null) {
            return businessDaily;
        }
        Cadence weekly = Weekly.tryMatch(dates);
        if (weekly != null) {
            return weekly;
        }
        Cadence monthly = Monthly.tryMatch(dates);
        if (monthly != null) {
            return monthly;
        }
        return Interval.tryMatch(dates);
    }

    /**
     * 신뢰도 = 표본 충분성 × 주기 규칙성 × 금액 안정성.
     *
     * <p>셋을 곱하는 이유는 <b>하나만 나빠도 신뢰할 수 없기 때문</b>이다. 평균을 쓰면
     * 금액이 10배씩 튀어도 표본이 많으면 높게 나온다.
     *
     * @param byWeekday 요일별 금액. 있으면 <b>요일마다 따로 재서 평균한다</b> — 아래 참고
     */
    private static BigDecimal score(int sampleCount, double regularity, BigDecimal min, BigDecimal max,
                                    Map<Integer, WeekdayAmount> byWeekday) {
        // 표본 3건에서 시작해 12건이면 만점. 그 위로는 더 늘지 않는다.
        double sampleFactor = Math.min(1.0, (sampleCount - MIN_SAMPLES + 1) / 10.0 + 0.5);

        double raw = sampleFactor * regularity * amountStability(min, max, byWeekday);
        return BigDecimal.valueOf(raw).setScale(4, RoundingMode.HALF_UP).min(BigDecimal.ONE);
    }

    /**
     * 금액 안정성. <b>예정 거래를 만들 때 쓰는 것과 같은 범위로 잰다.</b>
     *
     * <p>요일별 금액이 있으면 요일마다 재서 평균한다. 통짜 min/max 로 재면 요일 차이가
     * 폭으로 둔갑한다 — 신한카드 실측이 그 예다.
     *
     * <pre>
     * 통짜      110,600 ~ 582,100   5.26배   0.595
     * 요일별    월 1.28 · 화 1.28 · 수 1.25 · 목 1.25 · 금 1.29   평균 0.894
     * </pre>
     *
     * <p>582,100 은 연휴 뒤 화요일 한 건이고 110,600 은 금요일 최소값이다. 둘은 <b>서로
     * 다른 요일</b>이라 한 번도 같은 날 후보였던 적이 없는데, 통짜로 재면 이 둘의 비가
     * 패턴 전체의 점수를 정한다. 예정 거래는 이미 요일별 범위로 만들면서 점수만 통짜로
     * 재면 <b>화면의 범위와 그 범위에 매긴 신뢰도가 서로 다른 숫자를 보는</b> 셈이다.
     *
     * <p><b>요일 가중을 두지 않는다.</b> 표본이 24~26건으로 고르기도 하지만, 앞으로 30일에도
     * 요일은 고르게 오므로 단순 평균이 「하루치 기대값」이다.
     */
    private static double amountStability(BigDecimal min, BigDecimal max,
                                          Map<Integer, WeekdayAmount> byWeekday) {
        if (byWeekday == null || byWeekday.isEmpty()) {
            return spreadFactor(min, max);
        }
        double sum = 0.0;
        for (WeekdayAmount w : byWeekday.values()) {
            sum += spreadFactor(w.lo(), w.hi());
        }
        return sum / byWeekday.size();
    }

    /**
     * 금액 폭이 좁을수록 좋다. 2배 이상 벌어지면 0.5 아래로 떨어진다.
     *
     * <p>바닥 0.5 는 그대로 둔다. 요일로 갈라도 남는 폭은 진짜 변동이지만, 그렇다고
     * 반복이 아닌 것은 아니다 — 주기 규칙성이 그 축을 따로 잰다.
     */
    private static double spreadFactor(BigDecimal min, BigDecimal max) {
        if (min.compareTo(BigDecimal.ZERO) <= 0) {
            return 1.0;
        }
        double ratio = max.divide(min, 4, RoundingMode.HALF_UP).doubleValue();
        return 0.5 + 0.5 / Math.max(1.0, ratio);
    }

    /** 화면에 뜨는 이름. 적요 원문을 쓴다 — 정규화한 키는 숫자가 지워져 읽기 나쁘다. */
    private static String displayName(List<HistoricalTransaction> samples) {
        String raw = samples.get(samples.size() - 1).description();
        if (raw == null || raw.isBlank()) {
            return "이름 없는 반복 거래";
        }
        return raw.length() > 100 ? raw.substring(0, 100) : raw;
    }

    // --- 그룹 키 -------------------------------------------------------------

    private record GroupKey(Long accountId, Long categoryId, FlowType flowType, String descriptionKey) {

        static GroupKey of(HistoricalTransaction tx) {
            return new GroupKey(tx.accountId(), tx.categoryId(), tx.flowType(),
                    merchantKey(tx.description()));
        }
    }

    // --- 주기 판정기 ---------------------------------------------------------

    /**
     * @param regularity 0~1. 신뢰도 계산에 그대로 곱해진다
     */
    private record Cadence(CadenceType type, Integer intervalValue, Integer dayOfMonth,
                           Integer dayOfWeek, double regularity, LocalDate anchor) {

        LocalDate nextDateAfter(LocalDate last, LocalDate today) {
            LocalDate next = switch (type) {
                case WEEKLY -> last.plusWeeks(1);
                case MONTHLY -> onDayOfMonth(last.plusMonths(1), dayOfMonth);
                case INTERVAL -> last.plusDays(intervalValue);
                case BUSINESS_DAILY -> nextBusinessDay(last);
            };
            // 마지막 거래가 오래됐으면 과거 날짜가 나온다 — 오늘 이후로 밀어 준다.
            // 여기가 특히 여러 번 도는 자리라, 자른 날짜에서 다시 세면 드리프트가 누적된다.
            while (next.isBefore(today)) {
                next = switch (type) {
                    case WEEKLY -> next.plusWeeks(1);
                    case MONTHLY -> onDayOfMonth(next.plusMonths(1), dayOfMonth);
                    case INTERVAL -> next.plusDays(intervalValue);
                    case BUSINESS_DAILY -> nextBusinessDay(next);
                };
            }
            return next;
        }

        private static LocalDate nextBusinessDay(LocalDate from) {
            LocalDate next = from.plusDays(1);
            while (next.getDayOfWeek() == DayOfWeek.SATURDAY || next.getDayOfWeek() == DayOfWeek.SUNDAY) {
                next = next.plusDays(1);
            }
            return next;
        }
    }

    private static final class BusinessDaily {
        /**
         * 주말에 거래가 없고 영업일 대부분을 채우면 영업일 반복이다.
         *
         * <p>{@code INTERVAL: 1} 과 갈리는 지점이 주말이다 — 매일 반복이면 토·일에도 있다.
         */
        static Cadence tryMatch(List<LocalDate> dates) {
            long weekend = dates.stream().filter(BusinessDaily::isWeekend).count();
            if (weekend > 0) {
                return null;
            }
            LocalDate first = dates.get(0);
            LocalDate last = dates.get(dates.size() - 1);
            long businessDays = first.datesUntil(last.plusDays(1))
                    .filter(d -> !isWeekend(d)).count();
            if (businessDays < MIN_SAMPLES) {
                return null;
            }
            double coverage = (double) dates.size() / businessDays;
            if (coverage < BUSINESS_DAILY_COVERAGE) {
                return null;
            }
            return new Cadence(CadenceType.BUSINESS_DAILY, null, null, null,
                    Math.min(1.0, coverage), last);
        }

        static boolean isWeekend(LocalDate d) {
            return d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
        }
    }

    private static final class Weekly {
        static Cadence tryMatch(List<LocalDate> dates) {
            DayOfWeek dow = dates.get(0).getDayOfWeek();
            long matching = dates.stream().filter(d -> d.getDayOfWeek() == dow).count();
            double ratio = (double) matching / dates.size();
            if (ratio < 1.0) {
                return null;
            }
            // 같은 요일이어도 격주면 간격이 14일이다 — 그건 INTERVAL 이 맞다.
            List<Long> gaps = gaps(dates);
            if (gaps.stream().anyMatch(g -> g != 7L)) {
                return null;
            }
            return new Cadence(CadenceType.WEEKLY, null, null, dow.getValue(), 1.0,
                    dates.get(dates.size() - 1));
        }
    }

    private static final class Monthly {
        static Cadence tryMatch(List<LocalDate> dates) {
            int anchorDay = dates.get(0).getDayOfMonth();
            for (LocalDate d : dates) {
                if (Math.abs(d.getDayOfMonth() - anchorDay) > MONTHLY_DAY_TOLERANCE) {
                    return null;
                }
            }
            List<Long> gaps = gaps(dates);
            // 월 간격은 28~31 일로 흔들린다. 허용 폭을 여기서 넓힌다.
            if (gaps.stream().anyMatch(g -> g < 26 || g > 35)) {
                return null;
            }
            double regularity = 1.0 - (deviationRatio(gaps) / MAX_INTERVAL_DEVIATION) * 0.2;
            return new Cadence(CadenceType.MONTHLY, null, anchorDay, null,
                    Math.max(0.0, Math.min(1.0, regularity)), dates.get(dates.size() - 1));
        }
    }

    private static final class Interval {
        static Cadence tryMatch(List<LocalDate> dates) {
            List<Long> gaps = gaps(dates);
            double deviation = deviationRatio(gaps);
            if (deviation > MAX_INTERVAL_DEVIATION) {
                return null;
            }
            long average = Math.round(gaps.stream().mapToLong(Long::longValue).average().orElse(0));
            if (average <= 0) {
                return null;
            }
            return new Cadence(CadenceType.INTERVAL, (int) average, null, null,
                    1.0 - deviation, dates.get(dates.size() - 1));
        }
    }

    private static List<Long> gaps(List<LocalDate> dates) {
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < dates.size(); i++) {
            gaps.add(ChronoUnit.DAYS.between(dates.get(i - 1), dates.get(i)));
        }
        return gaps;
    }

    /** 평균 대비 표준편차 비율. 0 이면 완벽히 규칙적이다. */
    private static double deviationRatio(List<Long> gaps) {
        double mean = gaps.stream().mapToLong(Long::longValue).average().orElse(0);
        if (mean <= 0) {
            return Double.MAX_VALUE;
        }
        double variance = gaps.stream().mapToDouble(g -> Math.pow(g - mean, 2)).average().orElse(0);
        return Math.sqrt(variance) / mean;
    }

    /** 탐지 결과. 저장은 서비스가 한다 — 이 클래스는 DB 를 모른다. */
    /**
     * 한 요일의 금액 분위수.
     *
     * <p><b>최소·최대가 아니라 q10/q90 이다.</b> 실측에서 화요일 정산 24건 중 582,100원이
     * 한 건 있었는데, 최대로 잡으면 그 하나가 폭을 47만원으로 벌린다. q90 은 15만원이다.
     * 곡선 밴드가 일별 폭의 30일 누적이라 이 차이가 그대로 쌓인다.
     *
     * @param lo  q10
     * @param mid q50 — 예정 거래의 {@code amount_expected} 가 된다
     * @param hi  q90
     */
    public record WeekdayAmount(BigDecimal lo, BigDecimal mid, BigDecimal hi) {
    }

    public record DetectedPattern(
            Long accountId,
            Long categoryId,
            FlowType flowType,
            String name,
            CadenceType cadenceType,
            Integer intervalValue,
            Integer dayOfMonth,
            Integer dayOfWeek,
            /**
             * 기준일이 휴일일 때 앞뒤 어느 쪽으로 가나 — <b>과거 회차에서 읽어낸다</b>
             * ({@code fitMonthly}). 증거가 없으면 {@code NEXT_BUSINESS_DAY} 다.
             */
            HolidayRule holidayRule,
            BigDecimal amountMin,
            BigDecimal amountMax,
            /**
             * {@code BUSINESS_DAILY} 전용 요일별 금액. 그 외에는 {@code null} 이다.
             *
             * <p>키는 ISO 요일(1=월 … 7=일). 카드 정산은 주말·공휴일 매출이 다음 영업일로
             * 몰려 <b>월요일이 나머지의 3배</b>다(설계노트 G-2 「금액 축」). 범위 하나로
             * 뭉개면 중앙선이 요일마다 틀리고 밴드가 불필요하게 넓어진다.
             */
            Map<Integer, WeekdayAmount> amountByWeekday,
            LocalDate nextExpectedDate,
            BigDecimal confidence,
            List<Long> sampleTransactionIds) {
    }
}
