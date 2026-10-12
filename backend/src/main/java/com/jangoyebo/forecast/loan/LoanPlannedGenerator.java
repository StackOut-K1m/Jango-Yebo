package com.jangoyebo.forecast.loan;

import com.jangoyebo.core.Loans;
import com.jangoyebo.core.Loans.RepayingLoan;
import com.jangoyebo.forecast.engine.LoanRepaymentSchedule;
import com.jangoyebo.forecast.engine.LoanRepaymentSchedule.Installment;
import com.jangoyebo.forecast.planned.PlannedTransaction;
import com.jangoyebo.forecast.planned.PlannedTransactionRepository;
import com.jangoyebo.forecast.run.ForecastInputsChanged;
import org.springframework.context.ApplicationEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 대출 상환을 예정 거래로 만든다 (P1-13).
 *
 * <p>반복 패턴 생성기({@code RecurringPatternService#generatePlanned})와 같은 자리에 있지만
 * <b>추정이 아니라 계약을 옮긴다.</b> 패턴은 과거 거래에서 주기를 찾아 범위를 추정하고,
 * 대출은 개설 시점에 마지막 회차까지 금액이 정해져 있다 — 그래서 신뢰도도 범위도 없다.
 *
 * <h2>매일 만든다</h2>
 *
 * <p>금융망은 개설 다음날부터 <b>매일 08:30</b> 에 {@code (대출금+이자)/대출기간} 을 뺀다
 * (2026-09-15 확정, 기술스택 12장). 월 1회로 뭉개면 총액은 같아도 곡선 모양이 달라지고,
 * <b>매일 판정되는 자동이체 실패를 못 맞춘다.</b>
 *
 * <p>대신 지평({@value #HORIZON_DAYS}일) 안만 만든다. 1년 대출이면 회차가 365개인데
 * 곡선이 그만큼 보지 않는다.
 *
 * <h2>곡선에 넣지 않는 대출</h2>
 *
 * <p>{@link RepayingLoan#affectsForecast()} 가 거른다 — 출금계좌를 모르거나 <b>연동이 끊긴</b>
 * 경우다. 끊긴 계좌는 잔액이 더 이상 갱신되지 않아 시작 잔액에 들어가지 않는데, 그 계좌의
 * 지출만 곡선에 넣으면 <b>있지도 않은 공백이 생긴다.</b> 대출 자체는 남아 있으므로 화면이
 * 「예보에 반영되지 않음」을 보여줘야 한다(16.1).
 *
 * <h2>자동 반영은 절대 걸리지 않는다</h2>
 *
 * <p>여기서 만든 행은 {@code sourceType = LOAN} 이고, 자동 반영
 * ({@code findForExecutionWindow})은 {@code PATTERN}·{@code USER} 만 본다. <b>금융망이 스스로
 * 빼므로 우리가 또 빼면 하루에 두 번 나간다.</b>
 */
@Service
public class LoanPlannedGenerator {

    private static final Logger log = LoggerFactory.getLogger(LoanPlannedGenerator.class);

    /** 반복 패턴 생성기와 같은 값이어야 한다 — 두 출처의 예정 거래가 같은 창을 덮어야 곡선이 고르다. */
    static final int HORIZON_DAYS = 90;

    private final Loans loans;
    private final PlannedTransactionRepository planned;
    private final ApplicationEventPublisher events;

    public LoanPlannedGenerator(Loans loans, PlannedTransactionRepository planned,
                                ApplicationEventPublisher events) {
        this.loans = loans;
        this.planned = planned;
        this.events = events;
    }

    /**
     * 사업장의 모든 대출을 다시 만든다. 동기화 뒤에 부른다.
     *
     * <p><b>{@code REQUIRES_NEW} 여야 한다.</b> 부르는 쪽이
     * {@code @TransactionalEventListener(AFTER_COMMIT)} 라, 그 시점에는 방금 커밋된 트랜잭션의
     * 동기화가 아직 살아 있다. 기본값({@code REQUIRED})으로 두면 여기서 쓴 행이
     * <b>오류 없이 조용히 사라진다</b> — 예외도 로그도 없고 곡선만 안 바뀐다.
     * {@code LoanPlannedSeamTests} 가 이 자리를 지킨다.
     *
     * @return 만든 예정 거래 수
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int regenerate(Long businessId, LocalDate today) {
        // **사업장 단위로 먼저 비운다.** 대출 하나씩 지우면 완납·해지된 대출의 미래 회차가
        // 영영 남는다 — 그 대출은 목록에서 빠져 아래 반복문이 손댈 일이 없기 때문이다.
        planned.deleteRegenerableLoansOf(businessId, today);

        int total = 0;
        int skipped = 0;
        for (RepayingLoan loan : loans.repayingLoans(businessId)) {
            if (!loan.affectsForecast()) {
                skipped++;
                log.info("대출 {} — 예보에 반영하지 않는다 (계좌 {} · 연결 {})",
                        loan.loanId(), loan.withdrawalAccountId(), loan.connected());
                continue;
            }
            total += regenerateOne(businessId, loan, today);
        }

        // **곡선 재계산을 걸어야 한다.** 예정 거래만 새로 만들고 여기서 멈추면 곡선이 옛 것으로
        // 남는다 — 대출만 바뀌는 경로(금융망 동기화가 거래는 안 바꾸고 대출만 갱신)가 실제로
        // 있어서, 이 신호가 없으면 화면이 하루 낡은 곡선을 보여준다.
        events.publishEvent(ForecastInputsChanged.of(businessId, ForecastInputsChanged.Reason.LOAN_CHANGED));

        log.info("사업장 {} 대출 예정 거래 {}건 생성 — 반영 제외 {}건", businessId, total, skipped);
        return total;
    }

    /**
     * 한 대출의 회차를 다시 만든다.
     *
     * <p>지우는 것은 {@link #regenerate} 가 사업장 단위로 먼저 한다. 여기서는 만들기만 한다 —
     * 남아 있는 날짜({@code occupied})는 이미 일어났거나 사용자가 손댄 것이라 비켜 간다.
     */
    int regenerateOne(Long businessId, RepayingLoan loan, LocalDate today) {
        Set<LocalDate> occupied = planned.findOccupiedLoanDates(loan.loanId(), today);

        List<Installment> schedule = switch (loan.cycle()) {
            case DAILY -> LoanRepaymentSchedule.dailyFromTotal(
                    loan.startedOn(), loan.totalRepayable(), loan.periodDays());
            // 사장님이 직접 등록한 대출. 금융망 대출은 전부 DAILY 다.
            case MONTHLY, INTEREST_MONTHLY -> monthly(loan, today);
        };

        List<PlannedTransaction> batch = new ArrayList<>();
        for (Installment installment : LoanRepaymentSchedule.within(
                schedule, today, today.plusDays(HORIZON_DAYS))) {
            // 남은 행은 이미 일어났거나 사용자가 끈 것이다. 하나 더 만들면 곡선에 두 번 들어간다.
            if (occupied.contains(installment.dueDate())) {
                continue;
            }
            batch.add(PlannedTransaction.fromLoan(
                    businessId, loan.loanId(), name(loan),
                    installment.dueDate(), installment.amount(),
                    loan.withdrawalAccountId(), null));
        }
        planned.saveAll(batch);
        return batch.size();
    }

    /**
     * 월 1회 상환. <b>금융망 대출에는 쓰이지 않는다</b> — 사장님이 금융망 밖의 실제 대출을
     * 직접 등록했을 때만이다. 그 경우 금액을 사장님이 적으므로 계약에서 계산하지 않는다.
     */
    private static List<Installment> monthly(RepayingLoan loan, LocalDate today) {
        if (loan.monthlyPaymentDay() == null || loan.monthlyPaymentAmount() == null) {
            return List.of();
        }
        List<Installment> schedule = new ArrayList<>();
        LocalDate cursor = today.withDayOfMonth(
                Math.min(loan.monthlyPaymentDay(), today.lengthOfMonth()));
        if (cursor.isBefore(today)) {
            cursor = cursor.plusMonths(1);
        }
        LocalDate limit = today.plusDays(HORIZON_DAYS);
        while (!cursor.isAfter(limit) && !cursor.isAfter(loan.maturityDate())) {
            schedule.add(new Installment(cursor, loan.monthlyPaymentAmount()));
            LocalDate next = cursor.plusMonths(1);
            cursor = next.withDayOfMonth(Math.min(loan.monthlyPaymentDay(), next.lengthOfMonth()));
        }
        return schedule;
    }

    /** 달력·목록에 뜨는 이름. 회차마다 같아서 화면이 한 줄로 묶을 수 있다. */
    private static String name(RepayingLoan loan) {
        return loan.productName() + " 상환";
    }
}
