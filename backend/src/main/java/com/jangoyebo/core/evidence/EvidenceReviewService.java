package com.jangoyebo.core.evidence;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.tenancy.BusinessGuard;
import com.jangoyebo.core.evidence.EvidenceDtos.EvidenceDetail;
import com.jangoyebo.core.evidence.EvidenceDtos.EvidenceSummary;
import com.jangoyebo.core.evidence.EvidenceDtos.Extraction;
import com.jangoyebo.core.evidence.EvidenceDtos.ExtractionItem;
import com.jangoyebo.core.evidence.EvidenceDtos.ExtractionPatch;
import com.jangoyebo.core.evidence.EvidenceDtos.ItemPatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 증빙 목록·상세·검토 (API 13.2~13.3, P1-02).
 *
 * <p>이 세 경로가 한 덩어리로 막혀 있었다 — {@code EvidenceController} 에 업로드 하나뿐이라
 * ⓐ 검토 화면 ⓑ 검산 재실행 호출자 ⓒ <b>사용자 수정 이력</b>이 함께 서 있지 못했다. ⓒ 가
 * 급한 쪽이다: {@code EVIDENCE_CORRECTION} 테이블은 {@code V6} 에 있는데 INSERT 하는 코드가
 * 없어, <b>그 사이에 확정된 추출들의 수정 이력이 영영 비어 있게 된다</b>(합의 ⑦ — 실사용
 * 정확도의 분자다. 나중에 넣어도 그 구간은 복구되지 않는다).
 *
 * <h2>검토 화면이 쓰는 신호 (P1-02 완료 기준)</h2>
 *
 * <p>완료 기준은 <b>「필드 강조」</b>다(2026-09-11 확정). 좌표 하이라이트가 아니다 —
 * {@code boundingBox} 는 항상 null 이고 채울 공급자가 없다. 대신 두 신호를 내보낸다.
 *
 * <ul>
 *   <li>{@code validation.needsReview} — 검산이 걸린 건. 목록의 정렬·강조 신호다</li>
 *   <li>{@code fieldConfidence} — 낮은 필드를 강조. <b>게이트가 아니다</b>(정밀도 23~25%,
 *       기저 오류율 16.7% — 무작위와 다르지 않다)</li>
 * </ul>
 *
 * <p><b>자동 승인은 없다.</b> 모든 추출이 {@code EXTRACTED}(사용자 확인 대기)를 거친다 —
 * 회계에 영향을 주는 오류 6건(날짜 3·합계 3)을 결정적 검사가 <b>0/6</b> 잡는다.
 */
@Service
public class EvidenceReviewService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceReviewService.class);

    /** 13.3 이 허용하는 상태. 추출 전·실패 건을 확정하면 없는 값을 확정하게 된다. */
    private static final Set<EvidenceStatus> EDITABLE =
            EnumSet.of(EvidenceStatus.EXTRACTED, EvidenceStatus.CONFIRMED);

    /** 헤더에서 사용자가 고칠 수 있는 필드. 이름은 <b>계약 키 그대로</b>여야 한다(분석기 전제). */
    private static final String SUPPLIER_NAME = "supplierName";
    private static final String SUPPLIER_BUSINESS_NO = "supplierBusinessNo";
    private static final String ISSUED_DATE = "issuedDate";
    private static final String TOTAL_AMOUNT = "totalAmount";
    private static final String VAT_AMOUNT = "vatAmount";
    private static final String DISCOUNT_AMOUNT = "discountAmount";
    private static final String VALIDATION = "validation";
    private static final String FIELD_CONFIDENCE = "fieldConfidence";

    private final EvidenceRepository evidences;
    private final EvidenceExtractionRepository extractions;
    private final EvidenceItemRepository items;
    private final EvidenceCorrectionRepository corrections;
    private final TransactionEvidenceRepository links;
    private final ExtractionValidator validator;
    private final BusinessGuard guard;

    public EvidenceReviewService(EvidenceRepository evidences,
                                 EvidenceExtractionRepository extractions,
                                 EvidenceItemRepository items,
                                 EvidenceCorrectionRepository corrections,
                                 TransactionEvidenceRepository links,
                                 ExtractionValidator validator,
                                 BusinessGuard guard) {
        this.evidences = evidences;
        this.extractions = extractions;
        this.items = items;
        this.corrections = corrections;
        this.links = links;
        this.validator = validator;
        this.guard = guard;
    }

    // ------------------------------------------------------------------ 13.2 목록

    /**
     * 증빙 목록 (13.2).
     *
     * <p>기간은 <b>업로드 시각</b> 기준이다. 발행일은 추출 후에 채워지므로 그것으로 걸면
     * 갓 올린 증빙이 목록에서 사라진다.
     */
    @Transactional(readOnly = true)
    public Page<EvidenceSummary> list(Long businessId, LocalDate from, LocalDate to,
                                      EvidenceStatus status, EvidenceType type,
                                      Boolean linked, Boolean needsReview, Pageable pageable) {
        Page<Evidence> page = evidences.search(businessId,
                from == null ? null : from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                // 종료일을 포함하려면 다음 날 0시 미만이다. 쿼리가 `< toInstant` 다.
                to == null ? null : to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                status, type, linked, needsReview, pageable);

        // 목록 한 페이지에 대해 추출·연결을 한 번씩만 읽는다. 행마다 읽으면 N+1 이다.
        List<Long> ids = page.getContent().stream().map(Evidence::getId).toList();
        Map<Long, EvidenceExtraction> byEvidence = ids.isEmpty() ? Map.of()
                : extractions.findByEvidenceIdInAndCurrentTrue(ids).stream()
                .collect(Collectors.toMap(EvidenceExtraction::getEvidenceId, Function.identity()));
        Set<Long> linkedIds = ids.isEmpty() ? Set.of()
                : Set.copyOf(links.findLinkedEvidenceIds(ids));

        return page.map(e -> {
            EvidenceExtraction extraction = byEvidence.get(e.getId());
            Map<String, Object> header = extraction == null ? null : extraction.getHeaderData();
            return new EvidenceSummary(
                    e.getId(),
                    e.getEvidenceType().name(),
                    e.getOriginalFilename(),
                    EvidenceDtos.fileUrlOf(e.getId()),
                    e.getProcessingStatus().name(),
                    e.getIssuedOn(),
                    decimalOf(value(header, TOTAL_AMOUNT)),
                    // 컬럼에서 읽는다 — 정렬·필터가 보는 값과 화면에 뜨는 값이 같아야 한다.
                    // JSONB 쪽에서 읽으면 둘이 갈렸을 때 "정렬은 위인데 뱃지는 없다"가 된다.
                    e.getNeedsReview(),
                    linkedIds.contains(e.getId()),
                    e.getCreatedAt());
        });
    }

    // ------------------------------------------------------------------ 13.2 상세

    @Transactional(readOnly = true)
    public EvidenceDetail get(Long evidenceId) {
        Evidence evidence = find(evidenceId);
        EvidenceExtraction extraction =
                extractions.findByEvidenceIdAndCurrentTrue(evidenceId).orElse(null);

        return new EvidenceDetail(
                evidence.getId(),
                evidence.getEvidenceType().name(),
                evidence.getOriginalFilename(),
                EvidenceDtos.fileUrlOf(evidence.getId()),
                evidence.getProcessingStatus().name(),
                extraction == null ? null : toDto(extraction),
                links.findByEvidenceId(evidenceId).stream()
                        .filter(l -> l.getMatchStatus() == MatchStatus.LINKED)
                        .map(TransactionEvidence::getTransactionId)
                        .toList(),
                evidence.getCreatedAt());
    }

    // ------------------------------------------------------------------ 13.3 수정·확정

    /**
     * 추출 결과 수정·확정 (13.3).
     *
     * <p>순서가 있다 — 고친 값을 먼저 반영하고, <b>그 값으로</b> 검산을 다시 돌린 뒤 확정한다.
     * 반대로 하면 사용자가 금액을 바로잡았는데도 옛 검산 결과가 "여전히 어긋납니다"로 남는다.
     *
     * <p><b>고친 것이 없어도 확정을 기록한다</b>(합의 ⑦). 안 그러면 "고칠 것이 없던 추출"과
     * "아직 안 본 추출"이 구분되지 않아 실사용 정확도가 실제보다 높게 나온다.
     */
    @Transactional
    public EvidenceDetail patchExtraction(Long evidenceId, ExtractionPatch patch) {
        Evidence evidence = find(evidenceId);
        if (!EDITABLE.contains(evidence.getProcessingStatus())) {
            throw new BusinessException(ErrorCode.EVIDENCE_STATE_CONFLICT,
                    "추출이 끝나지 않은 증빙입니다. 현재 상태: " + evidence.getProcessingStatus());
        }
        EvidenceExtraction extraction = extractions.findByEvidenceIdAndCurrentTrue(evidenceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVIDENCE_STATE_CONFLICT,
                        "추출 결과가 없는 증빙입니다."));

        List<EvidenceItem> rows = items.findByExtractionIdOrderByIdAsc(extraction.getId());
        List<EvidenceCorrection> changes = new ArrayList<>();

        Map<String, Object> header = new LinkedHashMap<>(
                extraction.getHeaderData() == null ? Map.of() : extraction.getHeaderData());
        applyHeader(extraction.getId(), header, patch, changes);
        applyItems(extraction.getId(), rows, patch.items(), changes);
        // 발행일은 컬럼으로도 따라가야 한다 — 목록의 기간 필터가 JSONB 가 아니라 그쪽을 본다.
        evidence.correctIssuedOn(patch.issuedDate());

        // 고친 값으로 검산을 다시 돌린다. 워커가 없으면 null 이 오고 옛 결과가 그대로 남는다 —
        // 검토 저장이 워커 가용성에 묶이면 안 된다.
        Map<String, Object> revalidated = validator.revalidate(contractShape(header, rows));
        if (revalidated != null) {
            header.put(VALIDATION, revalidated);
            // 정렬 키도 같이 옮긴다. 안 그러면 사용자가 금액을 바로잡았는데 목록이 계속
            // "확인 필요" 로 올린다. 재검산을 못 했으면(null) 건드리지 않는다.
            evidence.updateReviewFlag(needsReviewOf(revalidated));
        }
        extraction.replaceHeader(header);

        if (!changes.isEmpty()) {
            corrections.saveAll(changes);
        }
        if (patch.isConfirm()) {
            evidence.confirm();
            extraction.confirm(guard.currentUserId(), Instant.now());
        }
        // 필드 값을 로그에 남기지 않는다(명세 20.1). 무엇을 고쳤는지는 EVIDENCE_CORRECTION 에 있다.
        log.info("증빙 {} 검토 저장 — extractionId={} 수정 {}건 확정={} 검산재실행={}",
                evidenceId, extraction.getId(), changes.size(), patch.isConfirm(),
                revalidated != null);

        return get(evidenceId);
    }

    // ------------------------------------------------------------------ 내부

    /**
     * 헤더 값을 반영하면서 바뀐 것만 이력으로 만든다.
     *
     * <p>{@code null} 은 "안 보냄"이다 — 화면이 고친 필드만 보내는 것이 정상 동선이라
     * null 을 지움으로 읽으면 모델이 제대로 읽은 값이 저장에서 사라진다.
     */
    private void applyHeader(Long extractionId, Map<String, Object> header,
                             ExtractionPatch patch, List<EvidenceCorrection> changes) {
        putText(extractionId, header, SUPPLIER_NAME, patch.supplierName(), changes);
        putText(extractionId, header, SUPPLIER_BUSINESS_NO, patch.supplierBusinessNo(), changes);
        putAmount(extractionId, header, TOTAL_AMOUNT, patch.totalAmount(), changes);
        putAmount(extractionId, header, VAT_AMOUNT, patch.vatAmount(), changes);
        putAmount(extractionId, header, DISCOUNT_AMOUNT, patch.discountAmount(), changes);

        if (patch.issuedDate() != null) {
            String after = patch.issuedDate().toString();
            String before = text(header.get(ISSUED_DATE));
            if (!Objects.equals(before, after)) {
                header.put(ISSUED_DATE, after);
                changes.add(EvidenceCorrection.changed(extractionId, ISSUED_DATE, before, after));
            }
        }
    }

    private void applyItems(Long extractionId, List<EvidenceItem> rows,
                            List<ItemPatch> patches, List<EvidenceCorrection> changes) {
        if (patches == null || patches.isEmpty()) {
            return;
        }
        Map<Long, Integer> indexById = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            indexById.put(rows.get(i).getId(), i);
        }

        for (ItemPatch p : patches) {
            Integer index = p.extractionItemId() == null ? null : indexById.get(p.extractionItemId());
            if (index == null) {
                // 남의 추출 행이거나 없는 id 다. 행 추가는 명세에 없으므로 조용히 건너뛴다.
                log.warn("추출 {} 에 없는 품목 행 {} 이 왔다 — 무시한다",
                        extractionId, p.extractionItemId());
                continue;
            }
            EvidenceItem row = rows.get(index);
            // 인덱스는 0-based 이고 items 의 순서다 — 워커 inconsistentRows 와 같은 기준이다.
            String prefix = "items[" + index + "].";
            recordText(extractionId, changes, prefix + "rawName", row.getRawName(), p.rawName());
            recordAmount(extractionId, changes, prefix + "quantity", row.getQuantity(), p.quantity());
            recordText(extractionId, changes, prefix + "unit", row.getUnit(), p.unit());
            recordAmount(extractionId, changes, prefix + "unitPrice", row.getUnitPrice(), p.unitPrice());
            recordAmount(extractionId, changes, prefix + "amount", row.getAmount(), p.amount());
            recordAmount(extractionId, changes, prefix + "discountAmount",
                    row.getDiscountAmount(), p.discountAmount());

            row.applyUserEdit(p.rawName(), p.quantity(), p.unit(),
                    p.unitPrice(), p.amount(), p.discountAmount());
        }
    }

    /**
     * 검산 재실행에 보낼 모양 (20.4 {@code extraction} 과 같다).
     *
     * <p>헤더를 통째로 실어 보낸다 — 워커 스키마가 모르는 키를 무시하므로 골라 담을 이유가
     * 없고, {@code taxableAmount}·{@code taxFreeAmount} 처럼 <b>검산에 꼭 필요한데 13.2 에는
     * 안 나가는 필드</b>가 빠지는 사고를 막는다.
     */
    private Map<String, Object> contractShape(Map<String, Object> header, List<EvidenceItem> rows) {
        Map<String, Object> shape = new LinkedHashMap<>(header);
        shape.remove(VALIDATION);   // 판정 결과를 입력으로 되돌려 보내지 않는다
        shape.put("items", rows.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rawName", row.getRawName());
            item.put("quantity", row.getQuantity());
            item.put("unit", row.getUnit());
            item.put("unitPrice", row.getUnitPrice());
            item.put("amount", row.getAmount());
            item.put("discountAmount", row.getDiscountAmount());
            return item;
        }).toList());
        return shape;
    }

    private Extraction toDto(EvidenceExtraction extraction) {
        Map<String, Object> header = extraction.getHeaderData();
        return new Extraction(
                extraction.getId(),
                stringOf(value(header, SUPPLIER_NAME)),
                stringOf(value(header, SUPPLIER_BUSINESS_NO)),
                dateOf(value(header, ISSUED_DATE)),
                decimalOf(value(header, TOTAL_AMOUNT)),
                decimalOf(value(header, VAT_AMOUNT)),
                decimalOf(value(header, DISCOUNT_AMOUNT)),
                items.findByExtractionIdOrderByIdAsc(extraction.getId()).stream()
                        .map(ExtractionItem::of).toList(),
                mapOf(value(header, FIELD_CONFIDENCE)),
                mapOf(value(header, VALIDATION)),
                extraction.getModelName(),
                extraction.getPromptVersion(),
                extraction.getPipeline(),
                extraction.getStatus(),
                extraction.getCreatedAt(),
                extraction.getConfirmedAt());
    }

    private Evidence find(Long evidenceId) {
        Evidence evidence = evidences.findById(evidenceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVIDENCE_NOT_FOUND));
        // 직접 경로다 — Hibernate 필터가 걸리지 않으므로 명시 검증이 필요하다.
        guard.assertOwned(evidence);
        // 지운 증빙은 없는 것과 같다 (13.5). 상세로 들어오면 404 다 — 목록에서 사라졌는데
        // 링크를 눌러 열리면 화면이 "지워졌다"와 "아직 있다" 중 무엇을 믿을지 알 수 없다.
        if (evidence.isDeleted()) {
            throw new BusinessException(ErrorCode.EVIDENCE_NOT_FOUND);
        }
        return evidence;
    }

    private void putText(Long extractionId, Map<String, Object> header, String key,
                         String after, List<EvidenceCorrection> changes) {
        if (after == null) {
            return;
        }
        String trimmed = after.trim();
        String before = text(header.get(key));
        if (Objects.equals(before, trimmed)) {
            return;
        }
        header.put(key, trimmed);
        changes.add(EvidenceCorrection.changed(extractionId, key, before, trimmed));
    }

    /**
     * 금액은 <b>수치로</b> 비교한다. {@code 156000} 과 {@code 156000.00} 은 같은 값이라
     * 문자열로 대조하면 고치지 않은 필드가 수정 이력에 쌓이고 정확도가 실제보다 낮게 나온다.
     */
    private void putAmount(Long extractionId, Map<String, Object> header, String key,
                           BigDecimal after, List<EvidenceCorrection> changes) {
        if (after == null) {
            return;
        }
        BigDecimal before = decimalOf(header.get(key));
        if (before != null && before.compareTo(after) == 0) {
            return;
        }
        header.put(key, after);
        changes.add(EvidenceCorrection.changed(extractionId, key, plain(before), plain(after)));
    }

    private void recordText(Long extractionId, List<EvidenceCorrection> changes,
                            String field, String before, String after) {
        if (after == null || after.isBlank() || Objects.equals(before, after.trim())) {
            return;
        }
        changes.add(EvidenceCorrection.changed(extractionId, field, before, after.trim()));
    }

    private void recordAmount(Long extractionId, List<EvidenceCorrection> changes,
                              String field, BigDecimal before, BigDecimal after) {
        if (after == null || (before != null && before.compareTo(after) == 0)) {
            return;
        }
        changes.add(EvidenceCorrection.changed(extractionId, field, plain(before), plain(after)));
    }

    private Object value(Map<String, Object> header, String key) {
        return header == null ? null : header.get(key);
    }

    /** 검산 결과에서 판정을 꺼낸다. 판정이 없으면 null 이고 {@code false} 로 접지 않는다. */
    private Boolean needsReviewOf(Map<String, Object> validation) {
        if (validation == null || !(validation.get("needsReview") instanceof Boolean flag)) {
            return null;
        }
        return flag;
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number ? plain(decimalOf(value)) : value.toString();
    }

    /** 지수 표기를 만들지 않는다. {@code 1.56E+5} 가 이력에 남으면 사람이 못 읽는다. */
    private String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private String stringOf(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private LocalDate dateOf(Object value) {
        String raw = stringOf(value);
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private BigDecimal decimalOf(Object value) {
        if (value instanceof BigDecimal d) {
            return d;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapOf(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }
}
