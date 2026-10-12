package com.jangoyebo.core.loan;

import com.jangoyebo.common.response.ApiResponse;
import com.jangoyebo.common.tenancy.BusinessGuard;
import com.jangoyebo.common.tenancy.CurrentBusiness;
import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.job.JobService;
import com.jangoyebo.core.job.JobType;
import com.jangoyebo.core.loan.LoanDtos.LoanItem;
import com.jangoyebo.core.loan.LoanDtos.LoanListResponse;
import com.jangoyebo.core.loan.LoanDtos.SyncAcceptedResponse;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 대출 현황 (API 16.1, P1-13).
 *
 * <p><b>마이너스통장은 아직 없다.</b> 기술스택 4.13 이 보류로 정리했고
 * ({@code MINUS_ACCOUNT_*} 14종이 대출과 같은 테이블에 담기지 않는다), 동기화도
 * {@code TERM_LOAN} 만 넣는다. 응답 필드는 자리를 잡아 뒀다.
 *
 * <p>수동 등록({@code POST /loans})은 열지 않았다. 금융망 동기화가 주 경로이고, 수동 등록은
 * 상환 스케줄을 사장님이 직접 적어야 해서 화면이 먼저 필요하다.
 */
@RestController
@RequestMapping("/api/v1")
public class LoanController {

    /** 「오늘」을 정하는 시간대. 금융망이 한국시간 벽시계로 상환한다. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final LoanRepository loans;
    private final JobService jobService;
    private final BusinessGuard guard;
    private final Clock clock;

    public LoanController(LoanRepository loans, JobService jobService,
                          BusinessGuard guard, Clock clock) {
        this.loans = loans;
        this.jobService = jobService;
        this.guard = guard;
        this.clock = clock;
    }

    @GetMapping("/businesses/{businessId}/loans")
    @Transactional(readOnly = true)
    public ApiResponse<LoanListResponse> list(@CurrentBusiness Long businessId) {
        LocalDate today = LocalDate.now(clock.withZone(KST));
        List<LoanItem> items = loans.findByBusinessIdOrderByIdAsc(businessId).stream()
                .map(loan -> toItem(loan, today))
                .toList();
        return ApiResponse.ok(new LoanListResponse(items));
    }

    /**
     * 금융망 대출 동기화 접수 (API 16.1).
     *
     * <p>{@code LOAN_08} 은 {@code userKey} 로 조회해 <b>사업장 개념이 없다</b> — 가게가 둘인
     * 사장님이면 두 가게 대출이 한 목록에 온다. 핸들러가 출금계좌로 사업장을 가른다.
     */
    @PostMapping("/businesses/{businessId}/loans/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<SyncAcceptedResponse> sync(@PathVariable Long businessId) {
        guard.assertOwnsBusiness(businessId);
        Job job = jobService.enqueue(businessId, guard.currentUserId(), JobType.LOAN_SYNC, Map.of());
        return ApiResponse.ok(new SyncAcceptedResponse(
                job.getId(), job.getJobType(), job.getStatus()));
    }

    private static LoanItem toItem(Loan loan, LocalDate today) {
        boolean daily = "DAILY".equals(loan.getRepaymentCycle());
        int periodDays = (int) java.time.temporal.ChronoUnit.DAYS.between(
                loan.getStartedOn(), loan.getMaturityDate());

        return new LoanItem(
                loan.getId(),
                "TERM_LOAN",
                loan.getLenderName(),
                loan.getProductName(),
                loan.getStatus(),
                loan.getPrincipalAmount(),
                loan.getTotalRepayable(),
                loan.getOutstandingBalance(),
                LoanDtos.toRate(loan.getInterestRate()),
                loan.getRepaymentCycle(),
                loan.getRepaymentType(),
                loan.getMonthlyPaymentDay(),
                loan.getMonthlyPaymentAmount(),
                daily ? dailyPayment(loan, periodDays) : null,
                loan.getWithdrawalAccountId(),
                loan.getStartedOn(),
                loan.getMaturityDate(),
                nextPaymentDate(loan, today, daily),
                loan.getOverdueDays(),
                "FIN_API",
                loan.getWithdrawalAccountId() != null,
                loan.getWithdrawalAccountId() == null
                        ? "상환금이 빠지는 계좌가 이 서비스에 연결되어 있지 않아 예보에 반영되지 않습니다."
                        : null);
    }

    /**
     * 하루 상환액. 화면이 회차 90건을 한 줄로 묶을 때 쓴다.
     *
     * <p>마지막 회차는 반올림 나머지를 흡수해 조금 다르지만, 여기서는 <b>대표값</b>이라
     * 내림한 값을 그대로 쓴다 — 정확한 회차는 예정 거래 목록에 있다.
     */
    private static BigDecimal dailyPayment(Loan loan, int periodDays) {
        if (loan.getTotalRepayable() == null || periodDays < 1) {
            return null;
        }
        return loan.getTotalRepayable()
                .divide(BigDecimal.valueOf(periodDays), 0, RoundingMode.DOWN);
    }

    /**
     * 다음 상환일.
     *
     * <p>{@code DAILY} 는 <b>내일</b>이다 — 금융망이 개설 다음날부터 매일 08:30 에 빼고,
     * 오늘 것은 이미 지나갔다고 본다. 개설 당일이면 첫 회차가 내일이라 같은 답이 된다.
     * 만기가 지났으면 없다.
     */
    private static LocalDate nextPaymentDate(Loan loan, LocalDate today, boolean daily) {
        if (!loan.isRepaying() || today.isAfter(loan.getMaturityDate())) {
            return null;
        }
        if (daily) {
            LocalDate next = today.plusDays(1);
            return next.isAfter(loan.getMaturityDate()) ? null : next;
        }
        if (loan.getMonthlyPaymentDay() == null) {
            return null;
        }
        LocalDate candidate = today.withDayOfMonth(
                Math.min(loan.getMonthlyPaymentDay(), today.lengthOfMonth()));
        if (!candidate.isAfter(today)) {
            LocalDate next = today.plusMonths(1);
            candidate = next.withDayOfMonth(
                    Math.min(loan.getMonthlyPaymentDay(), next.lengthOfMonth()));
        }
        return candidate.isAfter(loan.getMaturityDate()) ? null : candidate;
    }
}
