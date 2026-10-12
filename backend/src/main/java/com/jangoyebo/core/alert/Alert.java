package com.jangoyebo.core.alert;

import com.jangoyebo.common.domain.RiskLevel;

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

import java.util.Map;

/**
 * 범용 인앱 알림 (ERD {@code ALERT}, API 10.3).
 *
 * <p><b>{@code core} 스키마에 있다.</b> {@code CASH_GAP}·{@code SAFETY_LINE} 은 {@code forecast} 가,
 * {@code SETTLEMENT_MISMATCH}·{@code EVIDENCE_MISSING} 은 {@code core} 가 만든다.
 * 모듈 의존이 {@code forecast → core} 단방향이라 공용 테이블은 {@code core} 가 가져야 한다.
 *
 * <p><b>중복을 만들지 않고 갱신한다</b>(API 10.3). 같은 원인이면 {@code dedupKey} 가 같고,
 * {@code uk_alert_dedup} 이 {@code ACTIVE} 인 것에 한해 유일성을 강제한다 — 확인 처리된 알림은
 * 같은 키로 다시 뜰 수 있어야 한다(문제가 재발한 것이므로).
 */
@Entity
@Table(name = "alert", schema = "core")
public class Alert extends BaseEntity implements BusinessScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", nullable = false, updatable = false, length = 30)
    private AlertType alertType;

    /** 잔액과 무관한 알림은 null 이다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", length = 20)
    private RiskLevel riskLevel;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false)
    private String message;

    /** 화면이 이동할 대상. 예: {@code {"settlementId": 71}} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "related_ref")
    private Map<String, Object> relatedRef;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private Map<String, Object> detail;

    /** 같은 원인 판정 키. null 이면 중복 억제를 하지 않는다. */
    @Column(name = "dedup_key", length = 200)
    private String dedupKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AlertStatus status;

    protected Alert() {
    }

    private Alert(Long businessId, AlertType alertType, RiskLevel riskLevel, String title, String message,
                  Map<String, Object> relatedRef, Map<String, Object> detail, String dedupKey) {
        this.businessId = businessId;
        this.alertType = alertType;
        this.riskLevel = riskLevel;
        this.title = title;
        this.message = message;
        this.relatedRef = relatedRef;
        this.detail = detail;
        this.dedupKey = dedupKey;
        this.status = AlertStatus.ACTIVE;
    }

    /** 잔액과 무관한 알림({@code SETTLEMENT_MISMATCH} 등). 등급은 null 이다. */
    public static Alert of(Long businessId, AlertType type, String title, String message,
                           Map<String, Object> relatedRef, Map<String, Object> detail, String dedupKey) {
        return new Alert(businessId, type, null, title, message, relatedRef, detail, dedupKey);
    }

    /**
     * 잔액 기반 알림({@code CASH_GAP}·{@code SAFETY_LINE}). 등급이 응답에 그대로 나간다(API 10.3).
     */
    public static Alert of(Long businessId, AlertType type, RiskLevel riskLevel, String title, String message,
                           Map<String, Object> relatedRef, Map<String, Object> detail, String dedupKey) {
        return new Alert(businessId, type, riskLevel, title, message, relatedRef, detail, dedupKey);
    }

    /** 같은 원인이 다시 관측됐다. 새로 만들지 않고 내용만 갱신한다. */
    public void refresh(String title, String message, Map<String, Object> detail) {
        this.title = title;
        this.message = message;
        this.detail = detail;
    }

    /** 등급까지 바뀔 수 있는 알림용. 공백은 규모가 달라져도 등급이 항상 {@code GAP} 이다. */
    public void refresh(RiskLevel riskLevel, String title, String message, Map<String, Object> detail) {
        this.riskLevel = riskLevel;
        refresh(title, message, detail);
    }

    public void acknowledge() {
        this.status = AlertStatus.ACKNOWLEDGED;
    }

    /** 원인이 사라졌다. 확인 처리와 같은 상태로 내린다 — 삭제하지 않아 이력이 남는다. */
    public void resolve() {
        this.status = AlertStatus.ACKNOWLEDGED;
    }

    public Long getId() { return id; }
    @Override public Long getBusinessId() { return businessId; }
    public AlertType getAlertType() { return alertType; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public String getTitle() { return title; }
    public String getMessage() { return message; }
    public Map<String, Object> getRelatedRef() { return relatedRef; }
    public Map<String, Object> getDetail() { return detail; }
    public String getDedupKey() { return dedupKey; }
    public AlertStatus getStatus() { return status; }
}
