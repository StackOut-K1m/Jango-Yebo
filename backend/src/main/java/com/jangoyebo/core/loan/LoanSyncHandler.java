package com.jangoyebo.core.loan;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.core.fin.FinApiException;
import com.jangoyebo.core.fin.FinUserKeyService;
import com.jangoyebo.core.fin.LoanClient;
import com.jangoyebo.core.fin.LoanClient.LoanAccountRec;
import com.jangoyebo.core.fin.LoanClient.RepaymentRec;
import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.job.JobHandler;
import com.jangoyebo.core.job.JobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 금융망 대출 동기화 (P1-13).
 *
 * <p><b>트랜잭션을 열지 않는다.</b> {@code AccountSyncHandler} 와 같은 이유다 — 대출 수만큼
 * {@code LOAN_09} 를 왕복하므로 여기에 {@code @Transactional} 을 붙이면 커넥션을 오래 쥔다.
 * 짧은 트랜잭션은 전부 {@link LoanSyncService} 가 연다.
 *
 * <h2>사업장이 아니라 사용자 단위로 조회된다</h2>
 *
 * <p>{@code LOAN_08} 은 {@code userKey} 하나로 그 사람의 대출을 <b>전부</b> 준다. 가게가 둘인
 * 사장님이면 두 가게 대출이 한 목록에 섞여 온다. 그래서 이 Job 은 사업장 단위로 돌지만
 * <b>목록은 사용자 전체를 받고</b>, 각 대출을 출금계좌로 사업장에 배정한다.
 *
 * <p>그 결과 <b>다른 사업장 대출도 이 실행에서 같이 저장된다.</b> 중복 저장은
 * {@code uk_loan_external} 과 upsert 가 흡수하므로 안전하고, 오히려 사업장을 번갈아 켤 때마다
 * 서로의 대출이 최신이 된다.
 *
 * <p>다만 <b>완납 판정은 요청한 사업장만</b> 한다({@link LoanSyncService#closeDisappeared}).
 * 목록에 없다는 사실은 사용자 전체에 대해 참이지만, 다른 사업장의 대출을 이 실행이
 * 닫아도 되는지는 그 사업장이 요청했을 때 판단하는 편이 안전하다.
 */
@Component
public class LoanSyncHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(LoanSyncHandler.class);

    private final LoanClient loanClient;
    private final LoanSyncService syncService;
    private final LoanTransactionMatcher matcher;
    private final LoanProductSyncService productSync;
    private final FinUserKeyService finUserKeyService;

    public LoanSyncHandler(LoanClient loanClient, LoanSyncService syncService,
                           LoanTransactionMatcher matcher,
                           LoanProductSyncService productSync,
                           FinUserKeyService finUserKeyService) {
        this.loanClient = loanClient;
        this.syncService = syncService;
        this.matcher = matcher;
        this.productSync = productSync;
        this.finUserKeyService = finUserKeyService;
    }

    @Override
    public JobType jobType() {
        return JobType.LOAN_SYNC;
    }

    @Override
    public Outcome handle(Job job) {
        Long businessId = job.getBusinessId();
        String userKey = finUserKeyService.resolve(requester(job));

        List<LoanAccountRec> recs = loanClient.inquireLoanAccounts(userKey);
        log.info("사업장 {} 대출 동기화 — 금융망 목록 {}건", businessId, recs.size());

        // 대출 id 와 금융망 계좌번호를 짝으로 들고 간다 — LOAN_09 는 계좌번호로 조회한다.
        List<Synced> mine = new ArrayList<>();
        int unattached = 0;
        for (LoanAccountRec rec : recs) {
            Optional<Long> loanId = syncService.upsert(rec);
            if (loanId.isEmpty()) {
                unattached++;
                continue;
            }
            mine.add(new Synced(loanId.get(), rec.accountNo()));
        }

        int installments = 0;
        int excluded = 0;
        for (Synced synced : mine) {
            installments += collectRepayments(userKey, synced);
            // 회차를 읽은 **뒤에** 원장과 잇는다. 대출이 만든 거래가 예측에 남아 있으면
            // 곡선이 같은 상환을 두 번 빼고, 패턴으로 잡히면 자동 반영이 실제로 또 출금한다.
            excluded += matcher.exclude(synced.loanId());
        }

        Set<String> seen = LoanSyncService.seenNumbers(recs);
        int closed = syncService.closeDisappeared(businessId, seen);
        int products = syncProductsQuietly();

        // 커밋 뒤에 forecast 가 예정 거래를 다시 만든다. core 는 forecast 를 못 부른다.
        syncService.announce(businessId);

        log.info("사업장 {} 대출 동기화 완료 — 저장 {}건, 사업장 미상 {}건, 회차 {}건, "
                        + "예측 제외 {}건, 완납 {}건, 상품 {}건",
                businessId, mine.size(), unattached, installments, excluded, closed, products);

        return Outcome.completed(Map.of(
                "businessId", businessId,
                "loanCount", mine.size(),
                "unattachedCount", unattached,
                "installmentCount", installments,
                "excludedTransactionCount", excluded,
                "closedCount", closed,
                "productCount", products));
    }

    /**
     * 추천 후보가 될 상품 목록 ({@code LOAN_03}).
     *
     * <p><b>실패해도 동기화 전체를 죽이지 않는다.</b> 본체는 이 사업장의 대출이고 상품 목록은
     * 곁다리다 — 여기서 던지면 대출까지 되돌아간다. {@code AccountSyncHandler} 가 잔액 조회를
     * 조용히 접는 것과 같은 판단이다.
     *
     * <p>상품은 {@code userKey} 를 안 쓰는 앱 전역 목록이라 사업장마다 다시 받는 것이
     * 낭비처럼 보이지만, 동기화는 자주 도는 일이 아니고 <b>따로 도는 배치를 하나 더 만드는
     * 편이 잊히기 쉽다.</b>
     */
    private int syncProductsQuietly() {
        try {
            return productSync.sync(loanClient.inquireLoanProducts());
        } catch (FinApiException e) {
            log.warn("대출 상품 목록 조회 실패 code={} — 동기화는 계속한다", e.finCode());
            return 0;
        }
    }

    /**
     * 한 대출의 회차를 읽는다.
     *
     * <p><b>실패해도 동기화 전체를 죽이지 않는다.</b> 본체는 대출 목록이고 회차는 이력이라,
     * 여기서 던지면 대출 하나의 문제가 나머지 전부를 되돌린다 — {@code AccountSyncHandler} 가
     * 잔액 조회를 조용히 접는 것과 같은 판단이다.
     */
    private int collectRepayments(String userKey, Synced synced) {
        try {
            RepaymentRec rec = loanClient.inquireRepayments(userKey, synced.accountNo());
            if (rec == null) {
                return 0;
            }
            applyTotalsQuietly(synced.loanId(), rec.loanBalance(), rec.remainingLoanBalance());
            return syncService.persistRepayments(synced.loanId(), rec.rows());
        } catch (FinApiException e) {
            log.warn("대출 {} 상환 내역 조회 실패 code={} — 동기화는 계속한다", synced.loanId(), e.finCode());
            return 0;
        }
    }

    /** 우리 대출 id 와 금융망 대출 계좌번호. {@code LOAN_09} 가 후자로 조회한다. */
    private record Synced(Long loanId, String accountNo) {
    }

    /** {@code LOAN_09} 의 {@code loanBalance}(원금+이자)와 {@code remainingLoanBalance}. */
    private void applyTotalsQuietly(Long loanId, String total, String remaining) {
        if (total == null || total.isBlank()) {
            return;
        }
        try {
            syncService.applyTotals(loanId, new BigDecimal(total.trim()),
                    remaining == null || remaining.isBlank() ? null : new BigDecimal(remaining.trim()));
        } catch (NumberFormatException e) {
            log.warn("대출 {} 의 금액 \"{}\"·\"{}\" 을 읽을 수 없다", loanId, total, remaining);
        }
    }

    /**
     * {@code userKey} 를 누구 것으로 쓸지. {@code AccountSyncHandler} 와 같은 이유로
     * {@code params} 가 아니라 Job 컬럼을 쓴다.
     *
     * <p>배치를 붙이면 여기가 null 이 되므로 그때 사업장 소유자 userId 를 채워야 한다.
     */
    private Long requester(Job job) {
        Long requestedBy = job.getRequestedBy();
        if (requestedBy == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "작업 %d 에 요청자가 없어 userKey 를 정할 수 없다 — 배치를 붙일 때는 "
                            .formatted(job.getId()) + "사업장 소유자 userId 를 채운다.");
        }
        return requestedBy;
    }
}
