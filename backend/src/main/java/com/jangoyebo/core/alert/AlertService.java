package com.jangoyebo.core.alert;

import com.jangoyebo.common.domain.RiskLevel;
import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 알림 생성·해제. 다른 기능들이 부르는 진입점이다.
 *
 * <p><b>중복을 만들지 않는다</b>(API 10.3). 같은 원인({@code dedupKey})의 {@code ACTIVE} 알림이
 * 있으면 내용만 갱신한다 — 대사를 세 번 고치면 알림이 세 개 쌓이는 것을 막는다.
 */
@Service
public class AlertService {

    private final AlertRepository repository;

    public AlertService(AlertRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Alert raise(Long businessId, AlertType type, String dedupKey,
                       String title, String message,
                       Map<String, Object> relatedRef, Map<String, Object> detail) {
        return raise(businessId, type, null, dedupKey, title, message, relatedRef, detail);
    }

    /** 잔액 기반 알림은 위험 등급을 함께 담는다 (API 10.3). */
    @Transactional
    public Alert raise(Long businessId, AlertType type, RiskLevel riskLevel, String dedupKey,
                       String title, String message,
                       Map<String, Object> relatedRef, Map<String, Object> detail) {
        return repository.findByBusinessIdAndAlertTypeAndDedupKeyAndStatus(
                        businessId, type, dedupKey, AlertStatus.ACTIVE)
                .map(existing -> {
                    existing.refresh(riskLevel, title, message, detail);
                    return existing;
                })
                .orElseGet(() -> repository.save(
                        Alert.of(businessId, type, riskLevel, title, message, relatedRef, detail, dedupKey)));
    }

    /**
     * 원인이 사라졌을 때 내린다. 없으면 아무것도 하지 않는다.
     *
     * <p>대사를 고쳐 차이가 0 이 됐는데 알림이 그대로면 <b>해결한 것이 계속 경고로 남는다.</b>
     */
    @Transactional
    public void resolve(Long businessId, AlertType type, String dedupKey) {
        repository.findByBusinessIdAndAlertTypeAndDedupKeyAndStatus(
                businessId, type, dedupKey, AlertStatus.ACTIVE).ifPresent(Alert::resolve);
    }

    @Transactional
    public Alert findOwned(Long alertId) {
        return repository.findById(alertId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ALERT_NOT_FOUND));
    }
}
