package com.jangoyebo.forecast.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 대출 조건에서 상환 일정을 낸다. <b>순수 함수다</b> — DB·시계·네트워크를 안 건드린다.
 *
 * <h2>금융망 대출은 매일 갚는다</h2>
 *
 * <p>금융망 플로우 문서: 「개설일 <b>다음날부터 매일</b> {@code (대출금+이자)/대출기간} 으로
 * 계산되어 오전 08:30 에 수시입출금 계좌에서 상환됩니다.」 대출 기간은 2~365일이라
 * 다년 대출이 없다.
 *
 * <p>이름은 「원리금균등상환」({@code repaymentMethodTypeName})으로 오지만 원리금균등의 월별
 * 분할이 아니다. 총액을 먼저 확정하고 일수로 나눈 값이 매일 같은 금액으로 빠진다.
 *
 * <h2>이자는 플로우 문서의 식이 아니다 — 실측으로 확정했다</h2>
 *
 * <p>플로우 문서와 {@code LOAN_09} 설명은 「원금×(금리/100)×(일수/365)」라고 적었다. 그런데
 * 2026-09-15~16 에 대출 셋을 <b>실제로 실행</b>해 {@code LOAN_09.loanBalance}(원금+이자)를 읽으니
 * 셋 다 그 식의 <b>기간(일)배</b>였다 — 한 회차 이자를 낼 때 일수에 하루가 아니라 대출기간 전체를
 * 넣고 그것을 기간만큼 부과한 값이다.
 *
 * <pre>
 *   대출                      실측 총액       P×r×d/365    P×r×d²/365
 *   14일 · 19.5% · 10만          110,471        100,748       110,471  ✓
 *   90일 · 17.5% · 10만          488,356        104,315       488,356  ✓
 *   365일 · 8.4% · 500만    158,300,000      5,420,000   158,300,000  ✓
 * </pre>
 *
 * <p>그래서 두 식을 {@link InterestModel} 로 갈라 둔다. <b>금융망 대출({@link #daily})은
 * {@code FIN_NETWORK}</b> 다 — 실제로 그 돈이 빠지고, 16.3 추천·11.1 {@code LOAN_EXECUTION} 이
 * 「받으면 하루에 얼마 갚나」를 이 함수로 낸다. 시중은행 대출을 가정하는 {@link #monthly}·
 * {@link #interestOnlyMonthly} 는 {@code FLOW_DOC}(정상 단리)을 그대로 쓴다 — 그쪽은 금융망이 아니다.
 * 금융망이 식을 고치면 {@code daily} 의 기본값 한 곳만 바꾼다.
 *
 * <pre>
 *   이자     = InterestModel 이 정한다 (아래)          원 단위 반올림 한 번
 *   총 상환  = 원금 + 이자
 *   일 상환  = 총 상환 / 기간                        마지막 회차가 나머지를 흡수한다
 * </pre>
 *
 * <h2>회차를 원금·이자로 쪼개지 않는다</h2>
 *
 * <p>곡선이 쓰는 것은 <b>그날 얼마가 빠지는가</b> 하나다. 그리고 금융망도 회차 금액
 * ({@code paymentBalance})을 하나만 준다 — 쪼개면 우리가 지어낸 비율이 된다.
 *
 * <h2>왜 월 1회로 뭉개면 안 되는가</h2>
 *
 * <p>총액은 같지만 곡선 모양이 다르다 — 월 1회는 그날 절벽이고 매일은 완만한 우하향이다.
 * 그리고 <b>금융망은 매일 빼고 매일 실패를 판정한다.</b> 월말에 한 번 보는 곡선은 15일에
 * 잔액이 상환액 아래로 내려가는 것을 못 보고, 그 실패({@code LOAN_09} 의
 * 「자동이체 실패 잔액 부족」)가 이 제품이 막으려는 사건이다.
 *
 * <h2>반올림을 마지막 회차에 몰아넣는다</h2>
 *
 * <p>일 상환액을 원 단위로 반올림하면 기간을 곱했을 때 총액과 어긋난다. 매일 조금씩
 * 흘리면 1년이면 수백 원이 사라지고, <b>그 차이가 만기일 곡선에 남는다.</b> 그래서 앞
 * 회차는 내림한 값으로 두고 <b>마지막 회차가 남은 전부를 가져간다</b> — 금융망도 총액을
 * 먼저 정하고 나눈다.
 */
public final class LoanRepaymentSchedule {

    /** 금융망 이자 계산의 분모. 윤년이어도 365 다(플로우 문서 기준). */
    private static final BigDecimal DAYS_IN_YEAR = new BigDecimal("365");

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private LoanRepaymentSchedule() {
    }

    /**
     * 한 회차.
     *
     * @param dueDate 빠지는 날. 금융망은 08:30 에 시도하지만 곡선은 날짜 단위라 시각을 안 쓴다
     * @param amount  그날 빠지는 금액(원금+이자)
     */
    public record Installment(LocalDate dueDate, BigDecimal amount) {
    }

    /**
     * 이자를 어느 식으로 내는가. 클래스 머리말의 실측 표가 근거다.
     */
    public enum InterestModel {
        /**
         * 플로우 문서·{@code LOAN_09} 설명의 단리 일할 — {@code P × (r/100) × d / 365}.
         * 시중은행 대출을 가정하는 {@link #monthly}·{@link #interestOnlyMonthly} 가 쓴다.
         */
        FLOW_DOC,
        /**
         * 금융망이 실제로 청구하는 값 — {@code P × (r/100) × d² / 365}. 일할이자를 d 번 부과한 것과
         * 같다. 3점 실측(2026-09-15~16)과 원 단위까지 일치한다. {@link #daily} 의 기본값이다.
         */
        FIN_NETWORK
    }

    /**
     * 플로우 문서 식의 이자({@link InterestModel#FLOW_DOC}). 원 단위로 반올림한다.
     *
     * <p><b>금융망 대출에는 이 값을 쓰지 말 것</b> — 실물은 {@link InterestModel#FIN_NETWORK} 다.
     * 이 오버로드가 남아 있는 것은 시중은행 가정치({@link #monthly} 등)가 정상 단리를 쓰기 때문이다.
     *
     * @param principal         원금
     * @param annualRatePercent 연 금리(%) — 금융망은 0.1~20
     * @param days              대출 기간(일)
     */
    public static BigDecimal interestOf(BigDecimal principal, BigDecimal annualRatePercent, int days) {
        return interestOf(principal, annualRatePercent, days, InterestModel.FLOW_DOC);
    }

    /**
     * 모델을 골라 이자를 낸다. 원 단위 반올림은 <b>마지막에 한 번</b>이다 — 중간에서 반올림하면
     * 14일·10만·19.5% 가 110,472 나 110,473 이 되어 실측(110,471)과 어긋난다.
     */
    public static BigDecimal interestOf(BigDecimal principal, BigDecimal annualRatePercent, int days,
                                        InterestModel model) {
        BigDecimal yearly = principal.multiply(annualRatePercent).divide(HUNDRED, 10, RoundingMode.HALF_UP);
        BigDecimal d = BigDecimal.valueOf(days);
        BigDecimal scaled = switch (model) {
            case FLOW_DOC -> yearly.multiply(d);
            case FIN_NETWORK -> yearly.multiply(d).multiply(d);
        };
        return scaled.divide(DAYS_IN_YEAR, 0, RoundingMode.HALF_UP);
    }

    /**
     * 원금과 금리로 <b>금융망 일 상환</b> 일정을 만든다. <b>아직 받지 않은 대출</b>을 계산할 때 쓴다 —
     * 추천(16.3)과 What-if 의 {@code LOAN_EXECUTION}({@code DAILY}) 이 여기로 온다.
     *
     * <p>이자는 {@link InterestModel#FIN_NETWORK} 다. 이 함수가 그리는 곡선은 「그 대출을 받으면
     * 실제로 매일 얼마가 빠지나」라서, 실물이 청구하는 식을 써야 16.1 현황(금융망이 준 총액)과
     * 같은 숫자가 나온다. 포차 500만·365일·8.4% — 16.1 은 158,300,000, 이 함수도 158,300,000.
     *
     * <p>이미 받은 대출은 금융망이 총액을 주므로 {@link #dailyFromTotal} 을 쓴다.
     */
    public static List<Installment> daily(LocalDate startedOn, BigDecimal principal,
                                          BigDecimal annualRatePercent, int days) {
        return daily(startedOn, principal, annualRatePercent, days, InterestModel.FIN_NETWORK);
    }

    /** 모델을 명시하는 오버로드. 테스트와 「플로우 문서 식이었다면」 비교에 쓴다. */
    public static List<Installment> daily(LocalDate startedOn, BigDecimal principal,
                                          BigDecimal annualRatePercent, int days, InterestModel model) {
        if (principal == null || principal.signum() <= 0) {
            throw new IllegalArgumentException("원금이 %s 다".formatted(principal));
        }
        return dailyFromTotal(startedOn,
                principal.add(interestOf(principal, annualRatePercent, days, model)), days);
    }

    /**
     * 총 상환액을 일수로 나눈 일정. <b>이미 받은 대출</b>에 쓴다 —
     * {@code LOAN_08} 의 {@code loanBalance} 가 이미 원금+이자다.
     *
     * <p>첫 회차는 <b>개설 다음날</b>이다. 개설일 당일에는 빠지지 않는다 — 그날은 돈이
     * 들어오는 날이다.
     *
     * @param startedOn 개설일. 첫 상환은 그 다음날이다
     * @param total     원금+이자 총액
     * @param days      대출 기간(일). 그대로 회차 수가 된다
     * @return {@code days} 개의 회차. 합계가 {@code total} 과 <b>정확히</b> 같다
     * @throws IllegalArgumentException 기간이 1일 미만이거나 총액이 0 이하일 때
     */
    public static List<Installment> dailyFromTotal(LocalDate startedOn, BigDecimal total, int days) {
        if (days < 1) {
            throw new IllegalArgumentException("대출 기간이 %d일이다 — 회차를 만들 수 없다".formatted(days));
        }
        if (total == null || total.signum() <= 0) {
            throw new IllegalArgumentException("총 상환액이 %s 다".formatted(total));
        }

        // 앞 회차는 내림한다. 올림하면 마지막 회차가 음수가 될 수 있다.
        BigDecimal each = total.divide(BigDecimal.valueOf(days), 0, RoundingMode.DOWN);

        List<Installment> schedule = new ArrayList<>(days);
        BigDecimal left = total;
        for (int i = 1; i <= days; i++) {
            // 마지막 회차가 반올림으로 남은 것을 전부 가져간다. 매일 흘리면 만기 곡선이 어긋난다.
            BigDecimal amount = i == days ? left : each;
            left = left.subtract(amount);
            schedule.add(new Installment(startedOn.plusDays(i), amount));
        }
        return schedule;
    }

    /**
     * 월 1회 원리금 상환 일정. <b>What-if 의 {@code LOAN_EXECUTION} 전용 가정치다</b>(API 11.1).
     *
     * <p>금융망 대출은 전부 일 상환이라 {@link #daily} 로 충분했다. 이 함수가 필요한 것은
     * 시나리오뿐이다 — 사용자가 <b>시중은행에서 받을 대출</b>을 가정해 볼 수 있어야 하는데
     * 그쪽은 월 상환이 보통이고, 곡선 모양이 일 상환과 완전히 다르다(매달 절벽 대 완만한
     * 우하향). 실제로 받은 대출은 계약값({@code Loans.RepayingLoan.monthlyPaymentAmount})을
     * 쓰므로 여기로 오지 않는다.
     *
     * <p><b>이자는 단리 일할로 잡는다</b> — {@link #interestOf} 와 같은 식이다. 원리금균등의
     * 월 복리 상각표를 흉내 내지 않는 이유는 그 정밀도가 가짜이기 때문이다. 사용자가 넣은
     * 금리 자체가 가정이고, 여기서 보려는 것은 <b>매달 얼마가 빠져서 잔액이 어떻게 되는가</b>다.
     *
     * @param days 대출 기간(일). 회차 수는 {@code days / 30} 이며 최소 1회다
     */
    public static List<Installment> monthly(LocalDate startedOn, BigDecimal principal,
                                            BigDecimal annualRatePercent, int days) {
        if (days < 1) {
            throw new IllegalArgumentException("대출 기간이 %d일이다 — 회차를 만들 수 없다".formatted(days));
        }
        if (principal == null || principal.signum() <= 0) {
            throw new IllegalArgumentException("원금이 %s 다".formatted(principal));
        }
        // 시중은행 가정치라 플로우 문서 식(FLOW_DOC)을 그대로 둔다 — 금융망 식(FIN_NETWORK)은 daily 만.
        BigDecimal total = principal.add(interestOf(principal, annualRatePercent, days, InterestModel.FLOW_DOC));
        int count = Math.max(1, days / 30);

        // 앞 회차는 내림하고 마지막이 나머지를 가져간다 — dailyFromTotal 과 같은 이유다.
        BigDecimal each = total.divide(BigDecimal.valueOf(count), 0, RoundingMode.DOWN);
        List<Installment> schedule = new ArrayList<>(count);
        BigDecimal left = total;
        for (int i = 1; i <= count; i++) {
            BigDecimal amount = i == count ? left : each;
            left = left.subtract(amount);
            schedule.add(new Installment(startedOn.plusMonths(i), amount));
        }
        return schedule;
    }

    /**
     * 이자만 매달 내고 <b>만기에 원금을 한 번에</b> 갚는 일정 (마이너스통장·만기일시상환).
     *
     * <p>곡선에서 이 방식이 다르게 보이는 지점은 <b>만기일 하나</b>다. 매달 빠지는 이자는
     * 작아서 잔액이 완만하지만 만기에 원금 전체가 한 번에 나간다 — 30일 창 안에 만기가
     * 들어오면 그날이 곡선의 절벽이고, 들어오지 않으면 곡선은 「돈이 늘고 이자만 조금씩
     * 빠지는」 모양이라 <b>대출이 공짜처럼 보인다.</b> 그 오해를 만들지 않으려면 만기가
     * 창 밖이라는 사실을 호출자가 경고로 말해야 한다.
     *
     * @param days 대출 기간(일). 만기는 {@code startedOn + days} 다
     */
    public static List<Installment> interestOnlyMonthly(LocalDate startedOn, BigDecimal principal,
                                                        BigDecimal annualRatePercent, int days) {
        if (days < 1) {
            throw new IllegalArgumentException("대출 기간이 %d일이다 — 회차를 만들 수 없다".formatted(days));
        }
        if (principal == null || principal.signum() <= 0) {
            throw new IllegalArgumentException("원금이 %s 다".formatted(principal));
        }
        LocalDate maturity = startedOn.plusDays(days);
        BigDecimal monthlyInterest = principal
                .multiply(annualRatePercent).divide(HUNDRED, 10, RoundingMode.HALF_UP)
                .divide(BigDecimal.valueOf(12), 0, RoundingMode.HALF_UP);

        List<Installment> schedule = new ArrayList<>();
        for (int i = 1; ; i++) {
            LocalDate due = startedOn.plusMonths(i);
            if (!due.isBefore(maturity)) {
                break;
            }
            schedule.add(new Installment(due, monthlyInterest));
        }
        // 만기 회차는 원금 + 마지막 달 이자다. 같은 날 두 줄로 내보내면 원인 표시가 두 번 뜬다.
        schedule.add(new Installment(maturity, principal.add(monthlyInterest)));
        return schedule;
    }

    /**
     * 일정 중 <b>예보 지평 안에 드는 회차만</b> 골라 낸다.
     *
     * <p>1년짜리 대출은 회차가 365개인데 곡선은 30~90일만 본다. 전부 예정 거래로 만들면
     * 달력이 대출로 덮이고, 지평 밖 행은 아무 데도 쓰이지 않는다.
     *
     * @param from 기준일(포함) — 당일자도 아직 안 일어났으므로 넣는다
     * @param to   지평 마지막 날(포함)
     */
    public static List<Installment> within(List<Installment> schedule, LocalDate from, LocalDate to) {
        return schedule.stream()
                .filter(i -> !i.dueDate().isBefore(from) && !i.dueDate().isAfter(to))
                .toList();
    }
}
