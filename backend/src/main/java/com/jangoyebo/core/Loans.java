package com.jangoyebo.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 보유 대출 — {@code core} 모듈이 밖에 내놓는 공개 API.
 *
 * <p>{@link AccountBalances}·{@link TransactionHistory} 와 같은 이유로 여기 있다.
 * {@code core} 는 CLOSED 라 {@code core.loan.LoanRepository} 를 {@code forecast} 가
 * 참조할 수 없다({@code ModularityTests} 가 "non-exposed type" 으로 잡는다).
 *
 * <h2>왜 회차가 아니라 계약을 주는가</h2>
 *
 * <p>대출 상환은 <b>미래가 이미 확정된 돈</b>이다 — 원금·금리·기간이 정해지는 순간 마지막
 * 회차까지의 금액이 전부 결정된다. 그래서 과거를 볼 필요가 없고, 반복 패턴처럼 관측으로
 * 추정할 이유도 없다.
 *
 * <p>회차를 미리 저장해 두고 건네지 않는 것은 <b>1년 대출이면 365회차</b>이기 때문이다.
 * 곡선은 30~90일만 보므로 대부분이 쓰이지 않는다. 계약만 넘기고
 * {@code LoanRepaymentSchedule} 이 지평 안의 회차만 계산한다.
 */
public interface Loans {

    /**
     * 앞으로 돈이 빠질 대출.
     *
     * <p><b>{@code CLOSED} 가 아닌 것만 준다.</b> 연체({@code OVERDUE})도 포함한다 — 만기가
     * 지나도 갚지 않은 돈이 남아 있고, 그것이 곡선에서 사라지면 없는 여유가 생긴다.
     *
     * <p>곡선에 넣을 수 없는 대출도 준다 — 출금계좌를 모르거나 연동이 끊긴 경우다. 화면이
     * 「예보에 반영되지 않음」을 보여줘야 하므로 여기서 걸러 내지 않고, 넣을지는 부르는 쪽이
     * {@link RepayingLoan#affectsForecast()} 로 정한다.
     */
    List<RepayingLoan> repayingLoans(Long businessId);

    /**
     * 예정 거래를 만드는 데 필요한 것만.
     *
     * @param withdrawalAccountId 상환금이 빠지는 계좌. 우리 앱에 없으면 {@code null}
     * @param totalRepayable      원금+이자 총액({@code LOAN_08} 의 {@code loanBalance}).
     *                            <b>원금이 아니다</b> — 금융망은 원금을 주지 않는다.
     *                            일 상환액은 이 값을 {@link RepayingLoan#periodDays()} 로 나눈 것이다
     * @param interestRate        연 %
     * @param monthlyPaymentDay   {@link RepaymentCycle#MONTHLY} 계열만. {@code DAILY} 는 {@code null}
     * @param connected           출금계좌의 연동이 살아 있는가. <b>끊긴 계좌는 잔액이 갱신되지
     *                            않아 시작 잔액에 안 들어간다</b> — 지출만 곡선에 넣으면
     *                            없는 공백이 생긴다
     */
    record RepayingLoan(Long loanId, Long withdrawalAccountId, String productName,
                        RepaymentCycle cycle,
                        BigDecimal totalRepayable, BigDecimal interestRate,
                        LocalDate startedOn, LocalDate maturityDate,
                        Integer monthlyPaymentDay, BigDecimal monthlyPaymentAmount,
                        boolean overdue, boolean connected) {

        /**
         * 대출 기간(일). 금융망 {@code loanPeriod} 와 같은 값이어야 한다.
         *
         * <p>개설일은 세지 않는다 — 첫 상환이 <b>다음날</b>이라 회차 수가 곧 이 값이다.
         */
        public int periodDays() {
            return (int) ChronoUnit.DAYS.between(startedOn, maturityDate);
        }

        /**
         * 예정 거래를 만들 수 있는가.
         *
         * <p>계좌를 모르면 곡선에 넣을 자리가 없고, 총액을 모르면 얼마가 빠지는지 알 수 없다.
         */
        public boolean affectsForecast() {
            return withdrawalAccountId != null && connected
                    && totalRepayable != null && totalRepayable.signum() > 0
                    && periodDays() >= 1;
        }
    }

    /** 상환 주기. DB {@code ck_loan_cycle} 과 같은 값이다. */
    enum RepaymentCycle {

        /**
         * 금융망 대출의 실제 동작 — 개설 다음날부터 <b>매일</b> 08:30 에
         * {@code (대출금+이자)/대출기간} 이 빠진다.
         */
        DAILY,

        /** 사장님이 직접 등록한 대출. 월 상환일에 한 번. */
        MONTHLY,

        /** 마이너스통장 이자 납입. 원금 상환이 아니다(P1 보류). */
        INTEREST_MONTHLY
    }
}
