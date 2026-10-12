package com.jangoyebo.forecast.engine;

import com.jangoyebo.forecast.engine.LoanRepaymentSchedule.Installment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 대출 상환 일정 (P1-13). Docker 불필요 — 대출 조건만 받는 순수 함수다.
 */
class LoanRepaymentScheduleTests {

    private static final LocalDate 개설일 = LocalDate.of(2026, 9, 15);

    private static BigDecimal 원(String v) {
        return new BigDecimal(v);
    }

    @Test
    void 플로우_문서_식은_단리_일할이다() {
        // 500만원 · 180일 · 16.8%  →  5,000,000 × 0.168 × 180/365. 문서가 적은 식이고,
        // 시중은행 가정치(monthly)만 이 식을 쓴다 — 금융망 실물은 아래 FIN_NETWORK 다.
        assertThat(LoanRepaymentSchedule.interestOf(원("5000000"), 원("16.8"), 180))
                .isEqualByComparingTo("414247");
    }

    @Test
    void 금융망_식은_실측_세_점을_원_단위까지_재현한다() {
        // 2026-09-15~16 에 실제로 실행한 대출 셋의 LOAN_09.loanBalance(원금+이자).
        // 플로우 문서 식(100,748 · 104,315 · 5,420,000)과 다르고, 셋이 P×r×d²/365 한 식에 떨어졌다.
        assertThat(합계(LoanRepaymentSchedule.daily(개설일, 원("100000"), 원("19.5"), 14)))
                .as("전북 초단기 14일 19.5% 10만").isEqualByComparingTo("110471");
        assertThat(합계(LoanRepaymentSchedule.daily(개설일, 원("100000"), 원("17.5"), 90)))
                .as("싸피 비상소액 90일 17.5% 10만").isEqualByComparingTo("488356");
        assertThat(합계(LoanRepaymentSchedule.daily(개설일, 원("5000000"), 원("8.4"), 365)))
                .as("우리 일반대출 365일 8.4% 500만").isEqualByComparingTo("158300000");
    }

    @Test
    void 반올림은_마지막에_한_번이다() {
        // 일할이자 748.08 을 먼저 반올림해 14번 곱하면 110,472, 반올림 없이 곱하면 110,473 —
        // 둘 다 실측(110,471)이 아니다. d² 를 곱한 뒤 한 번만 반올림해야 맞는다.
        assertThat(LoanRepaymentSchedule.interestOf(원("100000"), 원("19.5"), 14,
                LoanRepaymentSchedule.InterestModel.FIN_NETWORK)).isEqualByComparingTo("10471");
    }

    @Test
    void 짧은_대출은_금리가_높아도_이자가_적다() {
        // 추천 순위를 금리로 매기면 안 되는 이유 — 실측 식에서는 더 극단적이다.
        // 금리가 2.3배인 쪽이 이자는 293분의 1이다 (d² 가 붙어 기간이 지배한다).
        BigDecimal 초단기 = LoanRepaymentSchedule.interestOf(원("1000000"), 원("19.5"), 14,
                LoanRepaymentSchedule.InterestModel.FIN_NETWORK);
        BigDecimal 일년 = LoanRepaymentSchedule.interestOf(원("1000000"), 원("8.4"), 365,
                LoanRepaymentSchedule.InterestModel.FIN_NETWORK);

        assertThat(초단기).isEqualByComparingTo("104712");
        assertThat(일년).isEqualByComparingTo("30660000");
        assertThat(초단기).isLessThan(일년);
    }

    @Test
    void 첫_회차는_개설_다음날이다() {
        // 개설일 당일에는 안 빠진다 — 그날은 돈이 들어오는 날이다.
        List<Installment> schedule = LoanRepaymentSchedule.daily(개설일, 원("5000000"), 원("16.8"), 180);

        assertThat(schedule).hasSize(180);
        assertThat(schedule.getFirst().dueDate()).isEqualTo(개설일.plusDays(1));
        assertThat(schedule.getLast().dueDate()).isEqualTo(개설일.plusDays(180));
    }

    @Test
    void 회차_합계가_총액에_정확히_맞는다() {
        // 여기가 이 클래스의 핵심이다. 매일 반올림해 흘리면 만기일 곡선이 어긋난다.
        BigDecimal 원금 = 원("5000000");
        BigDecimal 총액 = 원금.add(LoanRepaymentSchedule.interestOf(원금, 원("16.8"), 180,
                LoanRepaymentSchedule.InterestModel.FIN_NETWORK));

        List<Installment> schedule = LoanRepaymentSchedule.daily(개설일, 원금, 원("16.8"), 180);

        assertThat(합계(schedule)).isEqualByComparingTo(총액);
        // 5,000,000 + 5,000,000 × 0.168 × 180² / 365 = 5,000,000 + 74,564,384
        assertThat(총액).isEqualByComparingTo("79564384");
    }

    @Test
    void 금융망이_준_총액을_다시_계산하지_않는다() {
        // 이미 받은 대출은 LOAN_08 의 loanBalance 가 원금+이자다. 원금을 역산하면
        // 이자 반올림 때문에 정확히 되돌아오지 않는다 — 준 값을 그대로 나눈다.
        List<Installment> schedule = LoanRepaymentSchedule.dailyFromTotal(개설일, 원("5414247"), 180);

        assertThat(schedule).hasSize(180);
        assertThat(합계(schedule)).isEqualByComparingTo("5414247");
    }

    private static BigDecimal 합계(List<Installment> schedule) {
        return schedule.stream().map(Installment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void 나누어떨어지지_않아도_합계가_맞는다() {
        // 1,000,003원을 7일로 나누면 143,571.857… 이다. 마지막 회차가 나머지를 흡수해야 한다.
        BigDecimal 원금 = 원("1000003");

        List<Installment> schedule = LoanRepaymentSchedule.dailyFromTotal(개설일, 원금, 7);

        assertThat(합계(schedule)).isEqualByComparingTo(원금);
        // 앞 회차는 모두 같은 금액이고, 마지막만 다르다.
        assertThat(schedule.subList(0, 6)).extracting(Installment::amount)
                .containsOnly(원("142857"));
        assertThat(schedule.getLast().amount()).isEqualByComparingTo("142861");   // 1,000,003 − 142,857×6
    }

    @Test
    void 마지막_회차가_음수가_되지_않는다() {
        // 앞 회차를 올림하면 마지막이 음수로 내려간다. 내림이라 그럴 수 없다.
        for (int days : new int[] {2, 3, 7, 13, 29, 31, 180, 365}) {
            List<Installment> schedule = LoanRepaymentSchedule.daily(개설일, 원("1234567"), 원("7.7"), days);
            assertThat(schedule.getLast().amount()).as("%d일".formatted(days)).isNotNegative();
        }
    }

    @Test
    void 매일_같은_금액이_빠진다() {
        // 월 1회 절벽이 아니라 완만한 우하향이다. 곡선 모양이 여기서 갈린다.
        List<Installment> schedule = LoanRepaymentSchedule.daily(개설일, 원("5000000"), 원("8.4"), 365);

        // (5,000,000 + 153,300,000) / 365 = 433,698.63… → 내림 433,698.
        // 포차 대출의 1회차가 2026-09-16 08:30 에 정확히 이 금액으로 빠졌다.
        assertThat(schedule.subList(0, 364)).extracting(Installment::amount)
                .containsOnly(원("433698"));
    }

    @Test
    void 지평_밖_회차는_걸러진다() {
        // 1년 대출이면 회차가 365개인데 곡선은 30일만 본다. 전부 예정 거래로 만들 이유가 없다.
        List<Installment> schedule = LoanRepaymentSchedule.daily(개설일, 원("5000000"), 원("8.4"), 365);

        List<Installment> 지평 = LoanRepaymentSchedule.within(schedule, 개설일, 개설일.plusDays(30));

        assertThat(지평).hasSize(30);   // 개설일 당일은 회차가 없다
        assertThat(지평.getFirst().dueDate()).isEqualTo(개설일.plusDays(1));
        assertThat(지평.getLast().dueDate()).isEqualTo(개설일.plusDays(30));
    }

    @Test
    void 기간이_없으면_거부한다() {
        assertThatThrownBy(() -> LoanRepaymentSchedule.dailyFromTotal(개설일, 원("1000000"), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0일");
        assertThatThrownBy(() -> LoanRepaymentSchedule.daily(개설일, BigDecimal.ZERO, 원("5"), 30))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- 시나리오(11.1)가 쓰는 가정치 ----------------------------------------------
    //
    // 금융망 대출은 전부 일 상환이라 아래 둘은 실제 대출에 쓰이지 않는다. 사용자가
    // 「시중은행에서 이런 조건으로 빌리면?」을 시험할 때만 쓴다.

    @Test
    void 월_상환은_기간을_삼십일로_나눈_횟수만큼_나온다() {
        List<Installment> schedule = LoanRepaymentSchedule.monthly(개설일, 원("12000000"), 원("6"), 365);

        assertThat(schedule).hasSize(12);
        assertThat(schedule.getFirst().dueDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(schedule.getLast().dueDate()).isEqualTo(LocalDate.of(2027, 9, 15));
    }

    @Test
    void 월_상환의_합계는_원금과_이자의_합과_정확히_같다() {
        BigDecimal 이자 = LoanRepaymentSchedule.interestOf(원("12000000"), 원("6"), 365);
        List<Installment> schedule = LoanRepaymentSchedule.monthly(개설일, 원("12000000"), 원("6"), 365);

        BigDecimal 합계 = schedule.stream()
                .map(Installment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        // 매 회차를 반올림하면 12번 흘린 차이가 만기 곡선에 남는다. 마지막이 나머지를 가져간다.
        assertThat(합계).isEqualByComparingTo(원("12000000").add(이자));
    }

    @Test
    void 한_달이_안_되는_월_상환도_한_번은_나온다() {
        List<Installment> schedule = LoanRepaymentSchedule.monthly(개설일, 원("1000000"), 원("6"), 20);

        assertThat(schedule).hasSize(1);
        assertThat(schedule.getFirst().dueDate()).isEqualTo(LocalDate.of(2026, 10, 15));
    }

    /**
     * 이자만 내다가 만기에 원금을 한 번에 갚는다.
     *
     * <p>곡선에서 이 방식이 다르게 보이는 지점은 <b>만기일 하나</b>다 — 그 전까지는 이자만
     * 빠져 완만하고, 만기에 절벽이 생긴다.
     */
    @Test
    void 이자만_내는_대출은_만기에_원금이_한_번에_빠진다() {
        List<Installment> schedule =
                LoanRepaymentSchedule.interestOnlyMonthly(개설일, 원("12000000"), 원("6"), 365);

        // 월 이자 = 12,000,000 × 0.06 / 12 = 60,000
        assertThat(schedule.getFirst().amount()).isEqualByComparingTo("60000");
        assertThat(schedule.getLast().dueDate()).isEqualTo(개설일.plusDays(365));
        assertThat(schedule.getLast().amount()).isEqualByComparingTo("12060000");
    }

    @Test
    void 이자만_내는_대출의_만기_전_회차는_전부_이자다() {
        List<Installment> schedule =
                LoanRepaymentSchedule.interestOnlyMonthly(개설일, 원("12000000"), 원("6"), 365);

        assertThat(schedule.subList(0, schedule.size() - 1))
                .allSatisfy(i -> assertThat(i.amount()).isEqualByComparingTo("60000"));
    }
}
