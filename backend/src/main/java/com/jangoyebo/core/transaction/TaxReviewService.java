package com.jangoyebo.core.transaction;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 종합소득세 검토 상태 (8.7).
 *
 * <p>계좌에서 나갔다는 사실만으로 필요경비가 되지 않는다. 어떤 지출은 사업 관련성이 애매하고,
 * 어떤 것은 개인 사용이고, 어떤 것은 세무사에게 물어야 한다. <b>그 판단을 서비스가 대신하지
 * 않는다</b>(SRS 1.3 제외 범위, 도메인 규칙 4) — 사람이 찍은 값을 그대로 나른다.
 *
 * <p>이 값이 내보내기 {@code 07_거래증빙_연결목록.xlsx} 의 「검토 상태」 열 원천이다.
 */
@Service
public class TaxReviewService {

    /**
     * 한 번에 바꿀 수 있는 최대 건수 (8.7).
     *
     * <p>내보내기 직전에 96건을 한 건씩 누르게 하면 기능이 안 쓰인다. 그래서 일괄이 기본
     * 경로이고, 200 은 한 화면에서 고를 수 있는 현실적인 상한이다.
     */
    private static final int MAX_BATCH = 200;

    private final TransactionRepository transactions;

    public TaxReviewService(TransactionRepository transactions) {
        this.transactions = transactions;
    }

    /**
     * 일괄 변경 (8.7).
     *
     * <p><b>남의 사업장 거래가 섞여 있어도 통째로 거절하지 않는다.</b> 되는 것은 반영하고
     * 안 되는 것만 {@code failed} 로 돌려준다 — 200건을 고른 사람에게 "하나가 이상하니 처음부터
     * 다시"라고 하면 그 화면은 쓰이지 않는다.
     */
    @Transactional
    public Result bulkUpdate(Long businessId, List<Long> transactionIds,
                             TaxReviewStatus status, String note) {
        if (transactionIds == null || transactionIds.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "거래를 하나 이상 골라 주세요.");
        }
        if (transactionIds.size() > MAX_BATCH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "한 번에 " + MAX_BATCH + "건까지 바꿀 수 있습니다.");
        }
        requireUserAssignable(status);

        Map<Long, Transaction> found = transactions.findAllById(transactionIds).stream()
                .collect(Collectors.toMap(Transaction::getId, Function.identity()));

        Instant now = Instant.now();
        List<Failed> failed = new ArrayList<>();
        int updated = 0;

        for (Long id : transactionIds) {
            Transaction tx = found.get(id);
            if (tx == null) {
                failed.add(new Failed(id, "TRANSACTION_NOT_FOUND"));
                continue;
            }
            if (!tx.getBusinessId().equals(businessId)) {
                // 남의 사업장 거래다. 있다는 사실도 알려 주지 않는다 — 같은 사유로 묶는다.
                failed.add(new Failed(id, "TRANSACTION_NOT_FOUND"));
                continue;
            }
            tx.reviewForTax(status, note, now);
            updated++;
        }

        return new Result(updated, failed.size(), failed);
    }

    /**
     * 증빙 연결이 바뀌었을 때 자동 판정을 다시 건다 (8.7).
     *
     * <p>자동으로 정할 수 있는 것은 <b>증빙이 붙어 있나</b> 하나뿐이다. 나머지 다섯 값은
     * 사람의 판단이라 서버가 만들지 않는다.
     *
     * <p>사용자가 지정해 둔 값은 건드리지 않는다 — 판단은 {@code Transaction} 안에 있다.
     */
    @Transactional
    public void refreshEvidenceJudgement(Long transactionId, boolean hasEvidence) {
        transactions.findById(transactionId)
                .ifPresent(tx -> tx.applyEvidenceJudgement(hasEvidence, Instant.now()));
    }

    /**
     * 사람이 찍을 수 있는 값인가 (8.7).
     *
     * <p>{@code EVIDENCE_CONFIRMED}·{@code EVIDENCE_MISSING} 는 <b>증빙 연결 여부로
     * 기계적으로 정해지는 사실</b>이다. 손으로 찍게 두면 증빙이 없는데도 「증빙 확인 완료」가
     * 되어 내보내기의 검토 상태 열이 거짓말을 한다.
     */
    private void requireUserAssignable(TaxReviewStatus status) {
        if (status == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "검토 상태를 골라 주세요.");
        }
        if (status == TaxReviewStatus.EVIDENCE_CONFIRMED
                || status == TaxReviewStatus.EVIDENCE_MISSING) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "증빙 확인 여부는 증빙을 연결하면 자동으로 정해집니다.");
        }
    }

    /** 일괄 변경 결과 (8.7). */
    public record Result(int updatedCount, int failedCount, List<Failed> failed) {
    }

    /** 반영하지 못한 건 (8.7). */
    public record Failed(Long transactionId, String reason) {
    }
}
