package com.jangoyebo.core.safety;

import com.jangoyebo.common.response.ApiResponse;
import com.jangoyebo.common.tenancy.CurrentBusiness;
import com.jangoyebo.core.SafetyLineChanged;
import com.jangoyebo.core.SafetyLineOutlook;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * 운영자금 안전선 (API 12.1).
 *
 * <p>추천값({@code /safety-line/recommendations})은 {@link SafetyLineRecommendationService} 가
 * 낸다 — 최근 30일 고정비 합계와, 그중 미루기 어려운 둘(급여·임차료)만 추린 값.
 *
 * <p>{@code current} 블록은 {@link SafetyLineOutlook} 이 채운다 — 값의 대부분이 예측 곡선에서
 * 나오는데 {@code core → forecast} 참조는 금지라 {@code core} 가 모양만 선언하고 구현을
 * {@code forecast} 에 맡긴다. 예측 실행이 아직 없으면 <b>키 자체를 빼</b> 프론트가 옵셔널로
 * 다루게 한다(구현현황 §2 {@code non_null} 정책).
 */
@RestController
@RequestMapping("/api/v1/businesses/{businessId}/safety-line")
public class SafetyLineController {

    private final SafetyBalancePolicyRepository repository;
    private final SafetyLineOutlook outlook;
    private final SafetyLineRecommendationService recommendations;
    private final ApplicationEventPublisher events;

    public SafetyLineController(SafetyBalancePolicyRepository repository,
                                SafetyLineOutlook outlook,
                                SafetyLineRecommendationService recommendations,
                                ApplicationEventPublisher events) {
        this.repository = repository;
        this.outlook = outlook;
        this.recommendations = recommendations;
        this.events = events;
    }

    /**
     * 추천값 (12.1).
     *
     * <p><b>근거가 없는 후보는 키를 뺀다.</b> 0 원을 내보내면 화면이 「안전선 0 원이면 됩니다」로
     * 읽는데, 그건 「모른다」와 전혀 다른 말이다. 거래가 하나도 없는 새 사업장에서는
     * {@code referencePeriod} 만 남는다.
     */
    @GetMapping("/recommendations")
    public ApiResponse<Map<String, Object>> recommendations(@CurrentBusiness Long businessId) {
        return ApiResponse.ok(recommendations.recommend(businessId));
    }

    /**
     * 미설정이면 {@code 404} 가 아니라 <b>값이 빈 {@code 200}</b> 이다.
     *
     * <p>안전선을 아직 안 정한 것은 오류가 아니라 정상 상태고, 화면은 "설정하기" 유도를 띄운다.
     * 404 로 주면 프론트가 예외 경로로 다뤄야 한다(화면구성도 6.4 — 빈 상태는 throw 하지 않는다).
     *
     * <p><b>미설정이어도 {@code current} 는 채운다.</b> 「지금 잔고 500만 원, 30일 최저 120만 원」은
     * 안전선을 정하려는 사람에게 필요한 숫자다 — 그 화면에서 금액을 정하는 근거가 된다.
     */
    @GetMapping
    @Transactional(readOnly = true)
    public ApiResponse<SafetyLineResponse> get(@CurrentBusiness Long businessId) {
        SafetyBalancePolicy policy = repository.findByBusinessId(businessId).orElse(null);
        return ApiResponse.ok(response(businessId, policy));
    }

    /**
     * 전체 교체다. 사업장당 한 행이라 있으면 덮고 없으면 만든다(upsert).
     *
     * <p><b>바뀐 사실을 알린다.</b> 곡선은 다시 돌지 않지만(공통규약 2.6 — 안전선은 실행 트리거가
     * 아니다) 저장된 {@code SAFETY_LINE} 알림은 누가 고쳐 주지 않으면 옛 기준으로 남는다.
     * 듣는 쪽은 {@code SafetyLineTriggerListener} 다.
     */
    @PutMapping
    @Transactional
    public ApiResponse<SafetyLineResponse> put(@CurrentBusiness Long businessId,
                                               @Valid @RequestBody PutRequest request) {
        SafetyLineMethod method = request.method() == null ? SafetyLineMethod.MANUAL : request.method();
        boolean autoUpdate = Boolean.TRUE.equals(request.autoUpdate());

        SafetyBalancePolicy policy = repository.findByBusinessId(businessId)
                .map(existing -> {
                    existing.replace(method, request.amount(), autoUpdate);
                    return existing;
                })
                .orElseGet(() -> repository.save(
                        SafetyBalancePolicy.of(businessId, method, request.amount(), autoUpdate)));

        events.publishEvent(new SafetyLineChanged(businessId));
        return ApiResponse.ok(response(businessId, policy));
    }

    private SafetyLineResponse response(Long businessId, SafetyBalancePolicy policy) {
        BigDecimal amount = policy == null ? null : policy.getAmount();
        SafetyLineOutlook.Current current = outlook.of(businessId, amount).orElse(null);
        if (policy == null) {
            return SafetyLineResponse.unset(current);
        }
        return new SafetyLineResponse(amount, policy.getMethod(), policy.isAutoUpdate(),
                policy.getUpdatedAt(), current);
    }

    /**
     * @param amount 0 이상. <b>0 과 "설정 안 함"은 다르다</b> — 후자는 이 API 를 부르지 않는 것이다.
     */
    public record PutRequest(@NotNull @PositiveOrZero BigDecimal amount,
                             SafetyLineMethod method,
                             Boolean autoUpdate) {
    }

    public record SafetyLineResponse(BigDecimal amount, SafetyLineMethod method,
                                     Boolean autoUpdate, Instant updatedAt,
                                     SafetyLineOutlook.Current current) {

        /** 미설정. 네 필드가 모두 null 이라 응답에서 키가 전부 빠진다. */
        static SafetyLineResponse unset(SafetyLineOutlook.Current current) {
            return new SafetyLineResponse(null, null, null, null, current);
        }
    }
}
