package com.jangoyebo.core.alert;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    List<Alert> findByBusinessIdOrderByCreatedAtDesc(Long businessId);

    List<Alert> findByBusinessIdAndStatusOrderByCreatedAtDesc(Long businessId, AlertStatus status);

    /**
     * 같은 원인의 살아 있는 알림. {@code uk_alert_dedup} 과 조건이 같아야 한다 —
     * 이 조회가 빠지면 유니크 위반이 500 으로 나간다.
     */
    Optional<Alert> findByBusinessIdAndAlertTypeAndDedupKeyAndStatus(
            Long businessId, AlertType alertType, String dedupKey, AlertStatus status);

    /** 이번 실행에서 사라진 공백을 내릴 때 대상이 된다. */
    List<Alert> findByBusinessIdAndAlertTypeAndStatus(
            Long businessId, AlertType alertType, AlertStatus status);

    /**
     * 공백 키 → 알림. <b>상태를 가리지 않는다</b> — 사용자가 확인 처리해도 공백은 곡선에 남아
     * 있으므로 {@code cashGapId} 를 계속 붙여 줘야 한다. 같은 키가 여럿이면 최근 것이 뒤에 온다.
     */
    List<Alert> findByBusinessIdAndAlertTypeAndDedupKeyInOrderByIdAsc(
            Long businessId, AlertType alertType, Collection<String> dedupKeys);
}
