package com.jangoyebo.core.counterparty;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.response.ApiResponse;
import com.jangoyebo.common.response.PageResponse;
import com.jangoyebo.common.tenancy.BusinessGuard;
import com.jangoyebo.common.tenancy.CurrentBusiness;
import com.jangoyebo.core.category.TransactionCategory;
import com.jangoyebo.core.category.TransactionCategoryRepository;
import com.jangoyebo.core.counterparty.CounterpartyDtos.MerchantResponse;
import com.jangoyebo.core.counterparty.CounterpartyDtos.UpdateRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 거래처 사전 조회·수정 (API 8.6).
 *
 * <p><b>사전이 스스로 분류하기 때문에 필요한 문이다.</b> 사전에 잘못된 줄이 있으면 거래를
 * 하나씩 고쳐도 소용이 없다 — 그 건만 바뀌고 사전은 그대로라 다음 달 같은 적요에 또 같은
 * 분류가 붙는다. 근원을 고치는 곳이 여기다.
 *
 * <p>{@code avgDelayDays} 는 내보내지 않는다. ERD 가 {@code PLANNED_TRANSACTION} 매칭
 * 결과에서 집계한다고 정했고 그 경로(P1-05)가 아직 없다 — 없는 값을 0 으로 내보내면
 * "지연이 없다" 로 읽힌다.
 */
@RestController
@RequestMapping("/api/v1")
public class CounterpartyController {

    private final CounterpartyRepository repository;
    private final TransactionCategoryRepository categoryRepository;
    private final BusinessGuard guard;

    public CounterpartyController(CounterpartyRepository repository,
                                  TransactionCategoryRepository categoryRepository,
                                  BusinessGuard guard) {
        this.repository = repository;
        this.categoryRepository = categoryRepository;
        this.guard = guard;
    }

    /**
     * 사전 목록 (API 8.6).
     *
     * <p>{@code q} 는 표시 이름과 적요 양쪽을 본다 — 고친 이름으로도, 통장에 찍힌 문자열로도
     * 같은 줄을 찾아야 한다.
     */
    @GetMapping("/businesses/{businessId}/merchants")
    @Transactional(readOnly = true)
    public ApiResponse<PageResponse<MerchantResponse>> list(
            @CurrentBusiness Long businessId,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20) Pageable pageable) {

        Map<Long, String> codeById = categoryRepository.codeById();
        Page<MerchantResponse> page = repository
                .search(businessId, q == null || q.isBlank() ? null : q.strip(), pageable)
                .map(counterparty -> MerchantResponse.of(
                        counterparty, codeById.get(counterparty.getDefaultCategoryId())));
        return ApiResponse.ok(PageResponse.from(page));
    }

    /**
     * 사전 수정 (API 8.6).
     *
     * <p>명세가 <b>"수정하면 이후 동일 적요 거래는 사전 단계에서 자동 분류된다"</b> 고 정했다 —
     * 이미 분류된 거래는 소급해 바꾸지 않는다. 지난 분류까지 뒤집으면 사장님이 확인해 둔 것이
     * 말없이 달라진다.
     *
     * <p>사람이 고친 줄이므로 확정으로 센다. 모델이 담아 둔 줄을 고쳤다면 그 순간 사용자
     * 확정이 되고, 다음부터 확인을 묻지 않는다.
     */
    @PatchMapping("/merchants/{merchantId}")
    @Transactional
    public ApiResponse<MerchantResponse> update(@PathVariable Long merchantId,
                                                @Valid @RequestBody UpdateRequest request) {
        Counterparty counterparty = repository.findById(merchantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MERCHANT_NOT_FOUND));
        // 직접 경로다 — Hibernate 필터가 걸리지 않으므로 명시 검증이 필요하다.
        guard.assertOwned(counterparty);

        counterparty.updateByUser(request.displayName(), resolveCategoryId(request.defaultCategory()));

        return ApiResponse.ok(MerchantResponse.of(counterparty,
                categoryRepository.codeById().get(counterparty.getDefaultCategoryId())));
    }

    /**
     * 카테고리 코드를 id 로 바꾼다.
     *
     * <p><b>모르는 코드는 400 이다.</b> 조용히 무시하면 화면은 분류가 바뀐 줄 알고 사전은
     * 그대로라, 다음 달 같은 적요에 또 옛 분류가 붙는다.
     */
    private Long resolveCategoryId(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return categoryRepository.findByCodeAndActiveTrue(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "알 수 없는 카테고리입니다: " + code))
                .getId();
    }
}
