package com.jangoyebo.core.report;

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

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 주간·월간 지표와 AI 브리핑 스냅샷 (API 15.2 · P1-17 · {@code V35}).
 *
 * <p><b>지표와 문장이 한 행에 있다.</b> 명세가 「생성 실패 시 지표만이라도 반환한다」고
 * 정했으므로 <b>문장이 없는 브리핑은 오류가 아니라 정상 산출물</b>이다.
 *
 * <p>그래서 수명이 이렇다 — 지표를 먼저 저장해 {@code PENDING} 으로 두고, 워커 콜백이
 * 문장을 얹으면 {@code COMPLETED} 다. 워커가 실패해도 행은 남고 지표는 읽힌다.
 */
@Entity
@Table(name = "report", schema = "core")
public class Report implements BusinessScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_type", nullable = false, updatable = false, length = 20)
    private ReportType reportType;

    @Column(name = "period_start", nullable = false, updatable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false, updatable = false)
    private LocalDate periodEnd;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<Map<String, Object>> metrics;

    @Column(length = 200)
    private String headline;

    private String narrative;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportStatus status;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    protected Report() {
    }

    private Report(Long businessId, ReportType reportType, LocalDate periodStart,
                   LocalDate periodEnd, List<Map<String, Object>> metrics, Instant generatedAt) {
        this.businessId = businessId;
        this.reportType = reportType;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.metrics = metrics;
        this.status = ReportStatus.PENDING;
        this.generatedAt = generatedAt;
    }

    /** 지표만 든 브리핑. 문장은 아직 없다. */
    public static Report ofMetrics(Long businessId, ReportType reportType, LocalDate periodStart,
                                   LocalDate periodEnd, List<Map<String, Object>> metrics,
                                   Instant generatedAt) {
        return new Report(businessId, reportType, periodStart, periodEnd, metrics, generatedAt);
    }

    /**
     * 같은 기간을 다시 돌렸다 — 지표를 갈아 끼우고 문장을 지운다.
     *
     * <p><b>문장을 남겨 두면 안 된다.</b> 지난주 숫자로 쓴 문장이 이번 숫자 옆에 붙어 있으면
     * 둘이 어긋난 채로 읽힌다 — 브리핑에서 그건 거짓말이다.
     */
    public void refreshMetrics(List<Map<String, Object>> metrics, Instant generatedAt) {
        this.metrics = metrics;
        this.headline = null;
        this.narrative = null;
        this.status = ReportStatus.PENDING;
        this.generatedAt = generatedAt;
    }

    /**
     * 워커가 쓴 문장을 얹는다.
     *
     * <p>{@code partial} 이면 환각 숫자가 든 문장을 워커가 <b>이미 빼고</b> 보낸 것이다 —
     * 남은 문장은 그대로 쓰되 상태로 그 사실을 남긴다(20.7).
     */
    public void applySentence(String headline, String narrative, boolean partial) {
        this.headline = headline;
        this.narrative = narrative;
        this.status = partial ? ReportStatus.PARTIAL : ReportStatus.COMPLETED;
    }

    /**
     * 문장 생성이 실패했다.
     *
     * <p><b>지표는 지우지 않는다.</b> 명세가 「생성 실패 시 지표만이라도 반환한다」고 정했다 —
     * 화면은 그대로 숫자를 그린다.
     */
    public void markSentenceFailed() {
        this.status = ReportStatus.FAILED;
    }

    public Long getId() {
        return id;
    }

    @Override
    public Long getBusinessId() {
        return businessId;
    }

    public ReportType getReportType() {
        return reportType;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public List<Map<String, Object>> getMetrics() {
        return metrics;
    }

    public String getHeadline() {
        return headline;
    }

    public String getNarrative() {
        return narrative;
    }

    public ReportStatus getStatus() {
        return status;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }
}
