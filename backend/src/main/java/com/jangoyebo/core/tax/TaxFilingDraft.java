package com.jangoyebo.core.tax;

import com.jangoyebo.common.entity.BaseEntity;
import com.jangoyebo.common.tenancy.BusinessScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 종합소득세 신고서 초안 (P0-12 · API 14.3).
 *
 * <p><b>민감 항목 값은 담지 않는다.</b> 주소지(①)·생년월일(④)은 {@code fields} 에 키만 있고
 * 값은 항상 {@code null} 이다. 사용자는 내보내기 요청 본문에만 실어 보내고 그 값은 응답
 * 파일에만 존재한다.
 *
 * <p><b>근거 목록도 담지 않는다.</b> 어떤 거래가 어느 항목에 들어갔는지는 요청이 올 때
 * 매핑표로 다시 조회한다(14.3 {@code basis}). {@code fields.basisSummary} 는 생성 시점의
 * 건수 스냅샷일 뿐이다 — 저장해 두면 거래가 바뀌었을 때 근거와 화면이 어긋난다.
 */
@Entity
@Table(name = "tax_filing_draft", schema = "core")
public class TaxFilingDraft extends BaseEntity implements BusinessScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    /** 생성 Job. 행을 먼저 만들고 Job 을 걸어서 잠깐 비어 있다. */
    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "tax_year", nullable = false)
    private int taxYear;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaxDraftStatus status = TaxDraftStatus.PROCESSING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "hometax_reference")
    private Map<String, Object> hometaxReference;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> fields = Map.of();

    /** 사용자 수정값. <b>집계값과 분리 보관한다</b> — 덮어쓰면 근거 추적이 끊긴다. */
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> overrides;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "excluded_summary")
    private Map<String, Object> excludedSummary;

    @Column(name = "based_on_review_rate", nullable = false)
    private BigDecimal basedOnReviewRate = BigDecimal.ZERO;

    @Column(name = "generated_at")
    private Instant generatedAt;

    protected TaxFilingDraft() {
    }

    public static TaxFilingDraft queued(Long businessId, Long templateId, int taxYear,
                                        LocalDate periodStart, LocalDate periodEnd,
                                        Map<String, Object> hometaxReference) {
        TaxFilingDraft draft = new TaxFilingDraft();
        draft.businessId = businessId;
        draft.templateId = templateId;
        draft.taxYear = taxYear;
        draft.periodStart = periodStart;
        draft.periodEnd = periodEnd;
        draft.hometaxReference = hometaxReference;
        return draft;
    }

    /** 생성 Job 을 건 뒤 그 id 를 적는다. */
    public void attachJob(Long jobId) {
        this.jobId = jobId;
    }

    /**
     * 집계 결과를 적고 완료로 넘긴다.
     *
     * <p>JSONB 는 <b>맵 인스턴스를 갈아 끼워야</b> 더티 체킹이 걸린다. 내용만 바꾸면
     * Hibernate 가 변경을 못 본다 — {@code Job.addResult} 가 같은 이유로 그렇게 한다.
     */
    public void complete(Map<String, Object> fields, Map<String, Object> excludedSummary,
                         BigDecimal basedOnReviewRate, Instant generatedAt) {
        this.fields = Map.copyOf(fields);
        this.excludedSummary = excludedSummary == null ? null : Map.copyOf(excludedSummary);
        this.basedOnReviewRate = basedOnReviewRate;
        this.generatedAt = generatedAt;
        this.status = TaxDraftStatus.READY;
    }

    /**
     * 사용자 수정값을 얹는다 (14.3 {@code PATCH}).
     *
     * <p><b>집계값({@code fields})은 건드리지 않는다.</b> 한 칸에 덮어쓰면 「원래 얼마였는데
     * 무엇을 고쳤는지」가 사라져 근거 추적이 끊긴다. 화면이 보는 값은 조회 시점에 이 둘을
     * 겹쳐서 만든다.
     *
     * <p>보낸 항목만 바꾼다 — 한 칸을 고치려고 전체를 다시 보내야 하면 그 사이 다른 탭에서
     * 고친 값이 지워진다.
     */
    public void applyOverrides(Map<String, Object> incoming) {
        Map<String, Object> merged = new LinkedHashMap<>(
                overrides == null ? Map.of() : overrides);
        incoming.forEach((fieldKey, patch) -> {
            Map<String, Object> current = new LinkedHashMap<>();
            if (merged.get(fieldKey) instanceof Map<?, ?> existing) {
                existing.forEach((k, v) -> current.put(String.valueOf(k), v));
            }
            if (patch instanceof Map<?, ?> incomingField) {
                incomingField.forEach((k, v) -> current.put(String.valueOf(k), v));
            }
            merged.put(fieldKey, current);
        });
        this.overrides = Map.copyOf(merged);
    }

    /**
     * 직전 초안의 수정값을 물려받는다 (재생성).
     *
     * <p><b>재생성은 새 행을 만든다</b>(ERD — 이력). 그런데 사장님이 적어 둔 감가상각비·재고·
     * 세무조정을 함께 버리면, 거래 하나가 바뀔 때마다 그 입력을 처음부터 다시 해야 한다 —
     * 그러면 {@code STALE} 이 「버튼 한 번」이 아니라 「작업을 다시 하라」가 된다.
     *
     * <p>물려받은 값에는 {@code carriedOver} 를 남긴다. 화면이 「지난번에 적으신 값입니다」로
     * 구분해 줘야 사장님이 새 기간에 그대로 써도 되는지 판단할 수 있다.
     */
    public void inherit(Map<String, Object> previous) {
        if (previous == null || previous.isEmpty()) {
            return;
        }
        Map<String, Object> carried = new LinkedHashMap<>();
        previous.forEach((fieldKey, entry) -> {
            Map<String, Object> copy = new LinkedHashMap<>();
            if (entry instanceof Map<?, ?> map) {
                map.forEach((k, v) -> copy.put(String.valueOf(k), v));
            }
            copy.put("carriedOver", true);
            carried.put(fieldKey, copy);
        });
        this.overrides = Map.copyOf(carried);
    }

    /** 홈택스 참고값. 서비스는 신고 유형·기장의무를 판정하지 않는다 — 표시와 대사에만 쓴다. */
    public void updateHometaxReference(Map<String, Object> reference) {
        this.hometaxReference = reference == null ? null : Map.copyOf(reference);
    }

    public void fail() {
        this.status = TaxDraftStatus.FAILED;
    }

    /** 검토 상태가 바뀌어 근거가 달라졌다. 재생성으로 갱신한다(14.3). */
    public void markStale() {
        if (status == TaxDraftStatus.READY) {
            status = TaxDraftStatus.STALE;
        }
    }

    public Long getId() {
        return id;
    }

    @Override
    public Long getBusinessId() {
        return businessId;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public Long getJobId() {
        return jobId;
    }

    public int getTaxYear() {
        return taxYear;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public TaxDraftStatus getStatus() {
        return status;
    }

    public Map<String, Object> getHometaxReference() {
        return hometaxReference;
    }

    public Map<String, Object> getFields() {
        return fields;
    }

    public Map<String, Object> getOverrides() {
        return overrides;
    }

    public Map<String, Object> getExcludedSummary() {
        return excludedSummary;
    }

    public BigDecimal getBasedOnReviewRate() {
        return basedOnReviewRate;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }
}
