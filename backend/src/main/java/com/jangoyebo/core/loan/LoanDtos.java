package com.jangoyebo.core.loan;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jangoyebo.core.job.JobStatus;
import com.jangoyebo.core.job.JobType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * 대출 현황 (API 16.1).
 *
 * <p>{@code null} 필드는 응답에서 뺀다({@link JsonInclude}). 명세가 유형별로 다른 필드를
 * 보여주기 때문이다 — 일반 대출에 마이너스통장 한도가 {@code null} 로 끼면 화면이
 * 「한도 0원」으로 읽을 여지가 생긴다.
 */
public final class LoanDtos {

    private LoanDtos() {
    }

    public record LoanListResponse(List<LoanItem> items) {
    }

    /**
     * 한 대출.
     *
     * @param principal          최초 원금. <b>금융망 대출은 {@code null} 이다</b> —
     *                           {@code LOAN_08} 이 원금을 주지 않는다({@link #totalRepayable} 참조)
     * @param totalRepayable     원금+이자 총액. 일 상환액의 분자다
     * @param balance            남은 원금+이자
     * @param interestRate       <b>비율이다</b>(5.2% → {@code 0.052}). 명세 16.1 의 표기이고,
     *                           DB 는 금융망을 따라 퍼센트로 담는다 — 여기서 100 으로 나눈다
     * @param dailyPaymentAmount {@code DAILY} 대출의 하루 상환액. 화면이 회차를 한 줄로 묶을 때 쓴다
     * @param affectsForecast    예보 곡선에 반영되는가. 출금계좌를 모르거나 연동이 끊겼으면 {@code false}
     * @param forecastNote       반영되지 않는 이유. 반영되면 {@code null}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LoanItem(Long loanId,
                           String loanType,
                           String lenderName,
                           String productName,
                           String status,
                           BigDecimal principal,
                           BigDecimal totalRepayable,
                           BigDecimal balance,
                           BigDecimal interestRate,
                           String repaymentCycle,
                           String repaymentType,
                           Integer monthlyPaymentDay,
                           BigDecimal monthlyPaymentAmount,
                           BigDecimal dailyPaymentAmount,
                           Long withdrawalAccountId,
                           LocalDate startedOn,
                           LocalDate maturityDate,
                           LocalDate nextPaymentDate,
                           Integer overdueDays,
                           String source,
                           boolean affectsForecast,
                           String forecastNote) {
    }

    /** {@code POST /loans/sync} 응답. {@code AccountController} 의 동기화 접수와 같은 모양이다. */
    public record SyncAcceptedResponse(Long jobId, JobType jobType, JobStatus status) {
    }

    /**
     * 퍼센트를 비율로. 명세 16.1 이 {@code 0.052} 로 적는다.
     *
     * <p>DB 는 금융망이 주는 대로 퍼센트(16.8)를 담는다 — 바꿔 담으면 금융망 응답과 대조할 때
     * 매번 머릿속에서 환산해야 하고, 그 자리에서 자릿수를 틀린다. 변환은 경계에서 한 번만 한다.
     */
    public static BigDecimal toRate(BigDecimal percent) {
        return percent == null ? null : percent.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
    }
}
