package com.jangoyebo.core.alert;

import com.jangoyebo.common.domain.RiskLevel;

import com.jangoyebo.common.response.ApiResponse;
import com.jangoyebo.common.tenancy.BusinessGuard;
import com.jangoyebo.common.tenancy.CurrentBusiness;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 인앱 알림 (API 10.3).
 *
 * <p>이메일·푸시는 범위 밖이다(P2-05). 여기는 조회와 확인 처리뿐이다.
 *
 * <p>지금 실제로 알림을 만드는 것은 정산 대사 불일치({@code SETTLEMENT_MISMATCH})뿐이다.
 * 나머지 유형은 각 기능이 붙을 때 채워진다 — 목록 API 는 유형을 가리지 않으므로
 * 화면은 지금 붙여도 나중에 안 고친다.
 */
@RestController
@RequestMapping("/api/v1")
public class AlertController {

    private final AlertRepository repository;
    private final AlertService service;
    private final BusinessGuard guard;

    public AlertController(AlertRepository repository, AlertService service, BusinessGuard guard) {
        this.repository = repository;
        this.service = service;
        this.guard = guard;
    }

    @GetMapping("/businesses/{businessId}/alerts")
    @Transactional(readOnly = true)
    public ApiResponse<Map<String, List<AlertResponse>>> list(
            @CurrentBusiness Long businessId,
            @RequestParam(required = false) AlertStatus status) {

        List<Alert> alerts = status == null
                ? repository.findByBusinessIdOrderByCreatedAtDesc(businessId)
                : repository.findByBusinessIdAndStatusOrderByCreatedAtDesc(businessId, status);

        return ApiResponse.ok(Map.of("items", alerts.stream().map(AlertResponse::of).toList()));
    }

    @PostMapping("/alerts/{alertId}/ack")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void acknowledge(@PathVariable Long alertId) {
        Alert alert = service.findOwned(alertId);
        // 직접 경로다 — 남의 알림을 확인 처리하지 못하게 명시 검증한다.
        guard.assertOwned(alert);
        alert.acknowledge();
    }

    public record AlertResponse(Long alertId, AlertType type, RiskLevel riskLevel,
                                String title, String message,
                                Map<String, Object> relatedRef, Map<String, Object> detail,
                                AlertStatus status, Instant createdAt) {

        static AlertResponse of(Alert a) {
            return new AlertResponse(a.getId(), a.getAlertType(), a.getRiskLevel(),
                    a.getTitle(), a.getMessage(), a.getRelatedRef(), a.getDetail(),
                    a.getStatus(), a.getCreatedAt());
        }
    }
}
