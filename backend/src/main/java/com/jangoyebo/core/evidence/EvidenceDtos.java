package com.jangoyebo.core.evidence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 증빙 목록·상세·검토 API 의 요청·응답 (API 13.2~13.3). */
public final class EvidenceDtos {

    private EvidenceDtos() {
    }

    /**
     * 목록 항목 (13.2).
     *
     * <p>{@code needsReview} 를 여기 싣는 것은 <b>검토 화면이 "먼저 볼 곳"을 이 값으로
     * 만들기 때문</b>이다(P1-02 완료 기준). 추출 상태 뱃지와 함께 뜬다.
     *
     * <p>서버가 정렬·필터한다 — {@code sort=needsReview,desc} 와 {@code needsReview=true} 둘 다
     * 된다({@code V9} 가 이 값을 {@code evidence} 의 컬럼으로 올렸다. 정본은 여전히
     * {@code header_data.validation} 이고 여기는 사본이다).
     *
     * <p><b>{@code null} 은 {@code false} 가 아니다.</b> 아직 추출 결과가 없다는 뜻이고,
     * 검산을 통과했다는 뜻이 아니다. 목록 뱃지도 그 셋을 구분해서 그려야 한다.
     *
     * <p>{@code fileUrl} 은 아직 없다 — 13.5 다운로드가 붙기 전이다(구현현황 3.17).
     */
    public record EvidenceSummary(Long evidenceId,
                                  String evidenceType,
                                  String fileName,
                                  String fileUrl,
                                  String ocrStatus,
                                  LocalDate issuedDate,
                                  BigDecimal totalAmount,
                                  Boolean needsReview,
                                  boolean linked,
                                  Instant uploadedAt) {
    }

    /**
     * 원본을 내주는 경로 (13.1 {@code fileUrl}).
     *
     * <p><b>공개 URL 이 아니다.</b> 인증이 헤더라 {@code <img src>} 로는 못 받는다 — 화면은
     * {@code fetch} → {@code blob} → object URL 로 받는다.
     *
     * <p>절대 URL 을 만들지 않는 이유는 호스트를 서버가 모르기 때문이다. nginx 뒤에 있고
     * 웹·앱이 서로 다른 오리진을 쓴다 — 각자 자기 {@code baseUrl} 에 이 경로를 붙인다.
     */
    static String fileUrlOf(Long evidenceId) {
        return "/api/v1/evidences/%d/file".formatted(evidenceId);
    }

    /** 상세 (13.2). {@code extraction} 은 추출 전이거나 실패했으면 없다. */
    public record EvidenceDetail(Long evidenceId,
                                 String evidenceType,
                                 String fileName,
                                 String fileUrl,
                                 String ocrStatus,
                                 Extraction extraction,
                                 List<Long> linkedTransactionIds,
                                 Instant uploadedAt) {
    }

    /**
     * 추출 결과 (13.2 {@code extraction}).
     *
     * <p>노출 필드는 합의 ①이 정했다 — {@code header_data} 에 워커 {@code data} 를 통째로
     * 담되 화면이 쓰는 것만 꺼낸다. {@code discountAmount}·{@code validation} 이 그때 추가됐다.
     *
     * <p><b>{@code boundingBox} 는 없다.</b> 값이 항상 null 이라 전역 {@code non_null} 정책으로
     * 키가 통째로 빠진다. P1-02 완료 기준이 「필드 강조」로 바뀐 것이 이 때문이다(13.2) —
     * 검토 화면은 {@code validation.needsReview} 정렬과 {@code fieldConfidence} 강조로 만든다.
     */
    public record Extraction(Long extractionId,
                             String supplierName,
                             String supplierBusinessNo,
                             LocalDate issuedDate,
                             BigDecimal totalAmount,
                             BigDecimal vatAmount,
                             BigDecimal discountAmount,
                             List<ExtractionItem> items,
                             Map<String, Object> fieldConfidence,
                             Map<String, Object> validation,
                             String modelName,
                             String promptVersion,
                             String pipeline,
                             String status,
                             Instant extractedAt,
                             Instant confirmedAt) {
    }

    public record ExtractionItem(Long extractionItemId,
                                 String rawName,
                                 BigDecimal quantity,
                                 String unit,
                                 BigDecimal unitPrice,
                                 BigDecimal amount,
                                 BigDecimal discountAmount,
                                 BigDecimal netAmount,
                                 Long matchedItemId,
                                 String matchedBy) {

        static ExtractionItem of(EvidenceItem item) {
            return new ExtractionItem(item.getId(), item.getRawName(), item.getQuantity(),
                    item.getUnit(), item.getUnitPrice(), item.getAmount(),
                    item.getDiscountAmount(), item.netAmount(),
                    item.getItemId(), item.getMatchedBy());
        }
    }

    /**
     * 추출 결과 수정·확정 (13.3).
     *
     * <p><b>보낸 필드만 바꾼다.</b> {@code null} 은 "안 보냄"이고 "지움"이 아니다 — 화면이 고친
     * 필드만 보내는 것이 정상 동선이라, null 을 지움으로 읽으면 모델이 제대로 읽은 값이
     * 저장에서 사라진다. 값을 비우는 경로는 명세에 없다.
     *
     * <p>{@code confirmed = true} 면 {@code ocrStatus = CONFIRMED} 가 되고, <b>고친 것이 없어도
     * 확인 시각이 기록된다</b>(합의 ⑦ — 실사용 정확도의 분모다).
     */
    public record ExtractionPatch(String supplierName,
                                  String supplierBusinessNo,
                                  LocalDate issuedDate,
                                  BigDecimal totalAmount,
                                  BigDecimal vatAmount,
                                  BigDecimal discountAmount,
                                  List<ItemPatch> items,
                                  Boolean confirmed) {

        public boolean isConfirm() {
            return Boolean.TRUE.equals(confirmed);
        }
    }

    /** {@code extractionItemId} 가 없는 행은 무시한다 — 행 추가는 명세에 없다. */
    public record ItemPatch(Long extractionItemId,
                            String rawName,
                            BigDecimal quantity,
                            String unit,
                            BigDecimal unitPrice,
                            BigDecimal amount,
                            BigDecimal discountAmount) {
    }
}
