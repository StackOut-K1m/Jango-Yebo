package com.jangoyebo.core.loan;

import com.jangoyebo.core.LoansChanged;
import com.jangoyebo.core.account.AccountDirectory;
import com.jangoyebo.core.fin.LoanClient.LoanAccountRec;
import com.jangoyebo.core.fin.LoanClient.RepaymentRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 대출 동기화의 <b>짧은 트랜잭션들</b> (P1-13).
 *
 * <p>{@code AccountSyncService} 와 같은 이유로 {@code LoanSyncHandler} 에서 갈라냈다 —
 * 핸들러는 금융망을 대출 수만큼 왕복하므로 트랜잭션 밖에서 돌아야 한다.
 *
 * <h2>어느 사업장 것인지는 출금계좌가 정한다</h2>
 *
 * <p>{@code LOAN_08} 은 {@code userKey} 로 조회한다 — <b>사업장 개념이 없다.</b> 사장님이 가게를
 * 둘 가지고 있으면 두 가게의 대출이 한 목록에 섞여서 온다. 그래서
 * {@code withdrawalAccountNo} 를 우리 계좌로 되짚어 사업장을 정하고,
 * <b>그 계좌를 우리가 모르면 어디에도 붙이지 않는다.</b>
 *
 * <p>버리는 것이 아니라 <b>모른다고 말하는 것</b>이다. 아무 사업장에나 붙이면 남의 가게 곡선에
 * 없는 지출이 생기고, 기본 사업장에 몰아넣으면 가게가 둘인 사장님의 예보가 통째로 틀린다.
 */
@Service
public class LoanSyncService {

    private static final Logger log = LoggerFactory.getLogger(LoanSyncService.class);

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final LoanRepository loans;
    private final LoanRepaymentRepository repayments;
    private final AccountDirectory accounts;
    private final ApplicationEventPublisher events;

    public LoanSyncService(LoanRepository loans, LoanRepaymentRepository repayments,
                           AccountDirectory accounts, ApplicationEventPublisher events) {
        this.loans = loans;
        this.repayments = repayments;
        this.accounts = accounts;
        this.events = events;
    }

    /**
     * 동기화가 끝났다고 알린다. {@code forecast} 가 듣고 예정 거래를 다시 만든다.
     *
     * <p><b>커밋되는 트랜잭션 안에서 발행해야 한다</b> — 듣는 쪽이 {@code AFTER_COMMIT} 이라
     * 트랜잭션 밖이면 조용히 버려진다. 그래서 짧은 트랜잭션 하나로 감싼다.
     */
    @Transactional
    public void announce(Long businessId) {
        events.publishEvent(new LoansChanged(businessId));
    }

    /**
     * {@code LOAN_08} 한 행을 저장하거나 갱신한다.
     *
     * @return 붙일 사업장을 정하지 못했으면 비어 있다
     */
    @Transactional
    public Optional<Long> upsert(LoanAccountRec rec) {
        Optional<AccountDirectory.OwnedAccount> owner = accounts.findByExternalNumber(rec.withdrawalAccountNo());
        if (owner.isEmpty()) {
            log.info("대출 {} 는 우리가 모르는 계좌에서 빠진다 — 사업장을 정할 수 없어 건너뛴다",
                    mask(rec.accountNo()));
            return Optional.empty();
        }

        Long businessId = owner.get().businessId();
        String status = mapStatus(rec.status());
        // ⚠ LOAN_08 의 loanBalance 는 **원금**이다(2026-09-15 실측 — 500만 대출에 5,000,000).
        // 워크북 표에는 「대출금」이라고만 적혀 있어 LOAN_09 의 같은 이름 필드(원금+이자)와
        // 헷갈리기 쉽다. 총액은 LOAN_09 가 주므로 여기서는 원금만 채우고,
        // total_repayable 은 회차를 읽을 때 applyTotals 가 넣는다.
        BigDecimal principal = decimal(rec.loanBalance());

        Loan loan = loans.findByBusinessIdAndExternalLoanNo(businessId, rec.accountNo())
                .orElse(null);
        if (loan != null) {
            loan.syncStatus(status, null);
            return Optional.of(loan.getId());
        }

        Loan created = Loan.fromFinApi(businessId, rec.accountNo(),
                lenderOf(rec.accountName()), rec.accountName(),
                principal, decimal(rec.interestRate()), owner.get().accountId(),
                date(rec.loanDate()), date(rec.maturityDate()), status);
        return Optional.of(loans.save(created).getId());
    }

    /**
     * {@code LOAN_09} 회차를 쌓는다. 같은 회차를 다시 받으면 상태만 갱신한다 —
     * 실패가 나중에 성공으로 바뀔 수 있다.
     *
     * @return 이번에 새로 들어온 회차 수
     */
    @Transactional
    public int persistRepayments(Long loanId, List<RepaymentRow> rows) {
        int added = 0;
        for (RepaymentRow row : rows) {
            LocalDate due = date(firstNonBlank(row.repaymentActualDate(), row.repaymentAttemptDate()));
            if (due == null) {
                log.warn("대출 {} 회차 {} 에 날짜가 없다 — 건너뛴다", loanId, row.installmentNumber());
                continue;
            }
            Integer no = integerOrNull(row.installmentNumber());

            Optional<LoanRepayment> existing =
                    repayments.findByLoanIdAndDueDateAndInstallmentNo(loanId, due, no);
            if (existing.isPresent()) {
                existing.get().syncFrom(row.isSuccess(), row.failureReason());
                continue;
            }

            repayments.save(LoanRepayment.fromFinApi(loanId, no, due,
                    decimal(row.paymentBalance()), row.isSuccess(), row.failureReason()));
            added++;
        }
        return added;
    }

    /**
     * {@code LOAN_09} 가 주는 두 금액을 반영한다.
     *
     * <p><b>{@code LOAN_08} 과 {@code LOAN_09} 의 {@code loanBalance} 는 다른 값이다</b>
     * (2026-09-15 실측). 앞은 원금이고 뒤는 원금+이자다. 이름이 같아 표만 보면 알 수 없다.
     *
     * <p>⚠ 금융망 이자가 플로우 문서의 식과 맞지 않는다. 500만·365일·8.4% 가
     * {@code 158,300,000} 으로 왔는데 이는 {@code 5,000,000 + 420,000 × 365} 다 —
     * 한 회차 이자를 구할 때 일수에 하루가 아니라 <b>대출기간 전체</b>를 넣고 그것을 365번
     * 부과한 값이다. 단리 일할(542만)의 29배다. 이 가설은 2026-09-16 에 14일·90일 소액 대출 둘로
     * <b>확정</b>됐다 — {@code 총상환액 = P·(1 + r·d²/365)}, 세 점이 원 단위까지 맞는다
     * ({@code LoanRepaymentSchedule.InterestModel#FIN_NETWORK}). 미리보기(16.3·11.1)는 그 식으로
     * 계산하지만, <b>여기서는 여전히 다시 계산하지 않고 준 값을 쓴다</b> — 실제로 그 금액이
     * 계좌에서 빠지고, 금융망이 식을 바꾸면 준 값이 먼저 바뀌기 때문이다.
     *
     * @param total     {@code loanBalance} — 원금+이자
     * @param remaining {@code remainingLoanBalance} — 남은 원금+이자
     */
    @Transactional
    public void applyTotals(Long loanId, BigDecimal total, BigDecimal remaining) {
        loans.findById(loanId).ifPresent(loan -> loan.syncAmounts(total, remaining));
    }

    /**
     * 이번 조회에 <b>안 보인 대출을 닫는다.</b>
     *
     * <p>{@code LOAN_09} 설명이 근거다 — 「상환이 완료되면 대출 계좌는 <b>자동해지</b>되고,
     * 한번이라도 연체 상태가 있다면 연체금을 납입할 때까지 계좌는 유지됩니다.」 즉 금융망에
     * 「완납」 상태가 따로 없고 <b>목록에서 빠지는 것</b>이 완납 신호다.
     *
     * @param seen 이번 {@code LOAN_08} 에서 본 대출 계좌번호
     * @return 닫은 대출 수
     */
    @Transactional
    public int closeDisappeared(Long businessId, Set<String> seen) {
        List<Loan> closed = new ArrayList<>();
        for (Loan loan : loans.findByBusinessIdOrderByIdAsc(businessId)) {
            if (loan.isRepaying() && loan.getExternalLoanNo() != null
                    && !seen.contains(loan.getExternalLoanNo())) {
                loan.close();
                closed.add(loan);
            }
        }
        if (!closed.isEmpty()) {
            log.info("사업장 {} — 금융망 목록에서 사라진 대출 {}건을 닫았다", businessId, closed.size());
        }
        return closed.size();
    }

    /** 이번 동기화에서 본 대출 계좌번호. {@link #closeDisappeared} 의 입력이다. */
    public static Set<String> seenNumbers(List<LoanAccountRec> recs) {
        Set<String> seen = new HashSet<>();
        for (LoanAccountRec rec : recs) {
            seen.add(rec.accountNo());
        }
        return seen;
    }

    /**
     * 금융망 한국어 상태를 우리 값으로.
     *
     * <p>「완납」이 없다 — 다 갚은 대출은 목록에서 사라지므로 {@link #closeDisappeared} 가 맡는다.
     */
    static String mapStatus(String finStatus) {
        return switch (finStatus == null ? "" : finStatus.trim()) {
            case "연체" -> Loan.OVERDUE;
            case "상환중" -> Loan.REPAYING;
            default -> Loan.NORMAL;   // 「개설」과 모르는 값
        };
    }

    /**
     * 상품명에서 은행명을 짐작한다. {@code LOAN_08} 이 은행명을 안 준다 —
     * {@code accountTypeUniqueNo} 앞 세 자리가 은행코드지만 코드→이름 조회를 한 번 더 도는
     * 것보다 상품명을 쓰는 편이 화면에 낫다. 못 찾으면 상품명 그대로 둔다.
     */
    private static String lenderOf(String productName) {
        if (productName == null || productName.isBlank()) {
            return "금융기관";
        }
        int space = productName.indexOf(' ');
        return space > 0 ? productName.substring(0, space) : productName;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static LocalDate date(String ymd) {
        return ymd == null || ymd.isBlank() ? null : LocalDate.parse(ymd.trim(), YMD);
    }

    private static BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value.trim());
    }

    private static Integer integerOrNull(String value) {
        try {
            return value == null || value.isBlank() ? null : Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String mask(String accountNo) {
        return accountNo == null || accountNo.length() < 8
                ? "****"
                : accountNo.substring(0, 4) + "****" + accountNo.substring(accountNo.length() - 4);
    }
}
