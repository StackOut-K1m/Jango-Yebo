package com.jangoyebo.core.loan;

import com.jangoyebo.core.Loans.RepayingLoan;
import com.jangoyebo.core.Loans.RepaymentCycle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대출 상태 전이 (P1-13). Docker 불필요 — 엔티티 안의 판단만 본다.
 */
class LoanTests {

    private static final LocalDate 개설일 = LocalDate.of(2026, 9, 15);
    private static final LocalDate 만기일 = LocalDate.of(2027, 3, 14);   // 180일

    private static Loan 대출(String status) {
        // LOAN_08 의 loanBalance 는 **원금**이다(2026-09-15 실측). 총액은 LOAN_09 가 준다.
        return Loan.fromFinApi(2L, "0000000000000001", "싸피은행", "소상공인 비상자금",
                new BigDecimal("5000000"), new BigDecimal("16.8"),
                11L, 개설일, 만기일, status);
    }

    @Test
    void 만기가_지나면_닫는다() {
        Loan loan = 대출("REPAYING");

        assertThat(loan.closeIfMatured(만기일.plusDays(1))).isTrue();
        assertThat(loan.getStatus()).isEqualTo("CLOSED");
        assertThat(loan.isRepaying()).isFalse();
    }

    @Test
    void 만기일_당일에는_안_닫는다() {
        // 마지막 회차가 그날 빠진다. 하루 일찍 닫으면 그 금액이 곡선에서 사라진다.
        Loan loan = 대출("REPAYING");

        assertThat(loan.closeIfMatured(만기일)).isFalse();
        assertThat(loan.getStatus()).isEqualTo("REPAYING");
    }

    @Test
    void 연체는_만기가_지나도_안_닫는다() {
        // 갚지 않은 돈이 남아 있다. 닫으면 곡선에서 사라져 없는 여유가 생긴다.
        Loan loan = 대출("OVERDUE");

        assertThat(loan.closeIfMatured(만기일.plusDays(90))).isFalse();
        assertThat(loan.isRepaying()).isTrue();
    }

    @Test
    void 이미_닫힌_것을_두_번_닫지_않는다() {
        Loan loan = 대출("REPAYING");
        loan.closeIfMatured(만기일.plusDays(1));

        assertThat(loan.closeIfMatured(만기일.plusDays(2))).isFalse();
    }

    @Test
    void 목록_동기화는_금액을_안_바꾼다() {
        // LOAN_08 의 loanBalance 는 원금이고, 원금은 계약이라 움직이지 않는다.
        Loan loan = 대출("NORMAL");

        loan.syncStatus("OVERDUE", 3);

        assertThat(loan.getStatus()).isEqualTo("OVERDUE");
        assertThat(loan.getOverdueDays()).isEqualTo(3);
        assertThat(loan.getPrincipalAmount()).isEqualByComparingTo("5000000");
        assertThat(loan.getStartedOn()).isEqualTo(개설일);
        assertThat(loan.getMaturityDate()).isEqualTo(만기일);
    }

    @Test
    void 총액은_LOAN_09_가_준_값을_그대로_쓴다() {
        // 금융망 이자가 플로우 문서 식과 맞지 않는다 — 500만·365일·8.4% 가 1억 5,830만으로
        // 온다(단리 일할의 29배). 다시 계산하면 실제로 빠지는 금액과 어긋난다.
        Loan loan = 대출("REPAYING");

        loan.syncAmounts(new BigDecimal("158300000"), new BigDecimal("150000000"));

        assertThat(loan.getTotalRepayable()).isEqualByComparingTo("158300000");
        assertThat(loan.getOutstandingBalance()).isEqualByComparingTo("150000000");
    }

    @Test
    void 연체_이자가_붙으면_총액도_같이_오른다() {
        // 개설 시점 값으로 굳혀 두면 연체 이자가 곡선에서 빠진다.
        Loan loan = 대출("REPAYING");
        loan.syncAmounts(new BigDecimal("158300000"), new BigDecimal("150000000"));

        loan.syncAmounts(new BigDecimal("159000000"), new BigDecimal("151000000"));

        assertThat(loan.getTotalRepayable()).isEqualByComparingTo("159000000");
    }

    @Test
    void 금융망_대출은_매일_갚는다() {
        assertThat(대출("NORMAL").getRepaymentCycle()).isEqualTo("DAILY");
    }

    @Test
    void 기간은_개설일을_세지_않는다() {
        // 첫 상환이 다음날이라 회차 수가 곧 기간이다.
        RepayingLoan loan = 계약(11L, new BigDecimal("5000000"));

        assertThat(loan.periodDays()).isEqualTo(180);
        assertThat(loan.affectsForecast()).isTrue();
    }

    @Test
    void 우리가_모르는_계좌에서_빠지면_곡선에_넣지_않는다() {
        // 금융망 대출은 우리 앱에 등록하지 않은 계좌에서도 빠진다. 그 곡선은 우리가 그리지 않는다.
        assertThat(계약(null, new BigDecimal("5000000")).affectsForecast()).isFalse();
        assertThat(계약(11L, null).affectsForecast()).isFalse();
    }

    @Test
    void 연동이_끊긴_계좌의_대출도_곡선에_안_넣는다() {
        // 끊긴 계좌는 잔액이 갱신되지 않아 시작 잔액에 없다. 지출만 넣으면 없는 공백이 생긴다.
        RepayingLoan 끊김 = new RepayingLoan(1L, 11L, "소상공인 비상자금", RepaymentCycle.DAILY,
                new BigDecimal("5414247"), new BigDecimal("16.8"), 개설일, 만기일,
                null, null, false, false);

        assertThat(끊김.affectsForecast()).isFalse();
    }

    private static RepayingLoan 계약(Long accountId, BigDecimal totalRepayable) {
        return new RepayingLoan(1L, accountId, "소상공인 비상자금", RepaymentCycle.DAILY,
                totalRepayable, new BigDecimal("16.8"), 개설일, 만기일, null, null, false, true);
    }
}
