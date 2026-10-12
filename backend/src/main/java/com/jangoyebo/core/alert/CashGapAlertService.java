package com.jangoyebo.core.alert;

import com.jangoyebo.common.domain.RiskLevel;
import com.jangoyebo.core.CashGapAlerts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * {@link CashGapAlerts} 구현. {@link AlertService} 를 감싸 {@code CASH_GAP} 하나로 좁힌다.
 *
 * <p>{@code forecast} 는 {@code AlertService} 를 볼 수 없고, 보게 하더라도 알림 유형 전체를
 * 다룰 이유가 없다. 공백만 올리고 내리는 좁은 통로가 소유권을 분명하게 한다.
 */
@Service
public class CashGapAlertService implements CashGapAlerts {

    private final AlertService alerts;
    private final AlertRepository repository;

    public CashGapAlertService(AlertService alerts, AlertRepository repository) {
        this.alerts = alerts;
        this.repository = repository;
    }

    /** 공백의 등급은 정의상 항상 {@code GAP} 이다 — 0원 미만이라서 공백이다(API 2.6). */
    @Override
    @Transactional
    public Long raise(Long businessId, String dedupKey, String title, String message,
                      Map<String, Object> relatedRef, Map<String, Object> detail) {
        return alerts.raise(businessId, AlertType.CASH_GAP, RiskLevel.GAP, dedupKey,
                title, message, relatedRef, detail).getId();
    }

    @Override
    @Transactional
    public void resolveExcept(Long businessId, Set<String> keepDedupKeys) {
        for (Alert alert : repository.findByBusinessIdAndAlertTypeAndStatus(
                businessId, AlertType.CASH_GAP, AlertStatus.ACTIVE)) {
            if (!keepDedupKeys.contains(alert.getDedupKey())) {
                alert.resolve();
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Long> idsByDedupKey(Long businessId, Collection<String> dedupKeys) {
        if (dedupKeys.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> ids = new LinkedHashMap<>();
        // id 오름차순이라 같은 키가 여럿이면 뒤에 온 최근 것이 남는다.
        for (Alert alert : repository.findByBusinessIdAndAlertTypeAndDedupKeyInOrderByIdAsc(
                businessId, AlertType.CASH_GAP, new HashSet<>(dedupKeys))) {
            ids.put(alert.getDedupKey(), alert.getId());
        }
        return ids;
    }
}
