package com.jangoyebo.core.transaction;

import com.jangoyebo.common.domain.FlowType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.job.JobResponse;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/** 거래 API의 요청·응답 (API 명세 8.2~8.3). */
public final class TransactionDtos {

    private TransactionDtos() {
    }

    /**
     * 거래 목록 항목.
     *
     * <p>{@code merchantId}·{@code evidence} 는 각각 P1-01·P0-10 착수 시 채운다.
     * 지금은 프론트가 필드 존재를 전제로 개발할 수 있도록 형태만 유지한다.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TransactionResponse(
            Long transactionId,
            Long accountId,
            String finTransactionId,
            String counterpartyAccountNo,
            Instant occurredAt,
            String description,
            FlowType direction,
            BigDecimal amount,
            BigDecimal balanceAfter,
            String category,
            Classification classification,
            boolean isExcluded,
            TaxReview taxReview) {

        public static TransactionResponse of(Transaction t, String categoryCode) {
            return new TransactionResponse(
                    t.getId(), t.getAccountId(), t.getExternalTransactionId(),
                    t.getCounterpartyAccountNo(), t.getOccurredAt(), t.getDescription(),
                    t.getFlowType(), t.getAmount(), t.getBalanceAfter(), categoryCode,
                    new Classification(t.getClassificationStatus(),
                            t.getClassificationSource() == null ? null
                                    : t.getClassificationSource().apiValue(),
                            t.getClassificationConfidence(),
                            t.isNeedsConfirmation(),
                            t.getClassificationReason()),
                    t.isExcluded(),
                    new TaxReview(t.getTaxReviewStatus(), t.getTaxReviewNote(), t.getTaxReviewedAt()));
        }
    }

    /**
     * @param source 누가 분류했는가 (8.2). {@code AI}·{@code USER}·{@code RULE}·
     *               {@code DICTIONARY}. <b>DB 는 AI 를 {@code LLM} 으로 저장한다</b> —
     *               변환은 {@link ClassifierType#apiValue()} 한 곳에서만 한다
     * @param reason 모델이 준 설명. 사용자가 분류를 바꾸면 비운다 — 바뀐 분류에 옛 근거가
     *               붙지 않게 한다. 없으면 응답에 <b>키 자체가 없다</b>
     */
    public record Classification(ClassificationStatus status, String source, BigDecimal confidence,
                                 boolean needsConfirmation, String reason) {
    }

    /**
     * 종합소득세 검토 상태 (API 8.2·8.7).
     *
     * <p><b>지금은 읽기 전용이다.</b> P0-12 착수 전이라 값을 바꾸는 경로가 없어
     * {@code status} 는 항상 {@code UNREVIEWED} 이고 나머지 둘은 null 이다. 그래도 내보내는 이유는
     * 프론트가 검토 화면(P1-23)의 열을 미리 붙일 수 있게 하기 위해서다 —
     * 필터({@code taxReviewStatus})도 함께 열려 있다.
     */
    public record TaxReview(TaxReviewStatus status, String note, Instant reviewedAt) {
    }

    public record UpdateRequest(String category, @Size(max = 2000) String memo, Boolean isExcluded) {
    }

    /** CSV 임포트 결과 ({@code Job.result} 에 담긴다, API 7.4). */
    public record ImportResult(int importedCount, int duplicateCount, java.util.List<RejectedRow> rejectedRows) {
    }

    public record RejectedRow(int rowNumber, String reason) {
    }

    /**
     * 진행 중인 분류 작업 (API 8.5.1).
     *
     * <p><b>도는 것이 없어도 200 이다.</b> {@code running=false} 를 주고 {@code job} 키는
     * 아예 만들지 않는다 — 「분류가 안 돌고 있다」는 오류가 아니라 정상 상태다
     * (화면구성도 6.4). 404 로 주면 화면이 「기능 없음」과 구분하지 못한다.
     *
     * <p><b>{@code jobId} 로 진행률을 재지 않는다.</b> 한 작업이
     * {@code jango.classification.batch-size} 만큼만 맡고 서버가 다음 작업을 <b>새 id 로</b>
     * 이어 걸기 때문에, 이 id 가 {@code COMPLETED} 가 되어도 분류는 계속 돈다. 이 응답은
     * 「지금 돌고 있는가」 하나만 답한다 — 진행은 미분류 잔량으로 센다(8.5).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CurrentClassificationJob(boolean running, JobResponse job) {

        public static CurrentClassificationJob of(Job job) {
            return job == null
                    ? new CurrentClassificationJob(false, null)
                    : new CurrentClassificationJob(true, JobResponse.from(job));
        }
    }
}
