package com.jangoyebo.core.alert;

import com.jangoyebo.common.domain.RiskLevel;
import com.jangoyebo.core.SafetyLineAlerts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;

/**
 * {@link SafetyLineAlerts} 구현. {@link AlertService} 를 감싸 {@code SAFETY_LINE} 하나로 좁힌다
 * ({@link CashGapAlertService} 와 같은 모양).
 */
@Service
public class SafetyLineAlertService implements SafetyLineAlerts {

    private final AlertService alerts;
    private final AlertRepository repository;

    public SafetyLineAlertService(AlertService alerts, AlertRepository repository) {
        this.alerts = alerts;
        this.repository = repository;
    }

    /**
     * 등급이 바뀔 수 있어 {@code refresh} 가 등급까지 덮는다 — 공백과 다른 점이다. 같은 구간이
     * 깊어지면 {@code CAUTION} 이 {@code DANGER} 가 된다.
     */
    @Override
    @Transactional
    public Long raise(Long businessId, RiskLevel riskLevel, String dedupKey, String title, String message,
                      Map<String, Object> relatedRef, Map<String, Object> detail) {
        return alerts.raise(businessId, AlertType.SAFETY_LINE, riskLevel, dedupKey,
                title, message, relatedRef, detail).getId();
    }

    @Override
    @Transactional
    public void resolveExcept(Long businessId, Set<String> keepDedupKeys) {
        for (Alert alert : repository.findByBusinessIdAndAlertTypeAndStatus(
                businessId, AlertType.SAFETY_LINE, AlertStatus.ACTIVE)) {
            if (!keepDedupKeys.contains(alert.getDedupKey())) {
                alert.resolve();
            }
        }
    }
}
