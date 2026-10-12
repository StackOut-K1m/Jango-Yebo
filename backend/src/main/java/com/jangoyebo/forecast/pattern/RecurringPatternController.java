package com.jangoyebo.forecast.pattern;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.response.ApiResponse;
import com.jangoyebo.common.tenancy.BusinessGuard;
import com.jangoyebo.common.tenancy.CurrentBusiness;
import com.jangoyebo.core.CategoryCatalog;
import com.jangoyebo.forecast.pattern.RecurringPatternDtos.ConfirmRequest;
import com.jangoyebo.forecast.pattern.RecurringPatternDtos.DetectionResult;
import com.jangoyebo.forecast.pattern.RecurringPatternDtos.RecurringPatternResponse;
import com.jangoyebo.forecast.pattern.RecurringPatternDtos.UpdateRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 반복 패턴 (API 9.2~9.3, P0-03·04·05).
 *
 * <p><b>탐지는 배치가 아니라 수동 트리거다.</b> 언제 돌릴지가 정해지지 않았고
 * (거래 수집 직후? 하루 한 번?), 배치를 먼저 만들면 그 결정을 코드가 대신하게 된다.
 * 지금은 {@code POST .../recurring-patterns/detect} 로 부른다 — 명세에 없는 엔드포인트이며
 * 배치가 생기면 내부 호출로 바뀐다.
 */
@RestController
@RequestMapping("/api/v1")
public class RecurringPatternController {

    private final RecurringPatternService service;
    private final RecurringPatternRepository repository;
    private final CategoryCatalog categories;
    private final BusinessGuard guard;
    private final Clock clock;

    public RecurringPatternController(RecurringPatternService service,
                                      RecurringPatternRepository repository,
                                      CategoryCatalog categories,
                                      BusinessGuard guard,
                                      Clock clock) {
        this.service = service;
        this.repository = repository;
        this.categories = categories;
        this.guard = guard;
        this.clock = clock;
    }

    @GetMapping("/businesses/{businessId}/recurring-patterns")
    @Transactional(readOnly = true)
    public ApiResponse<Map<String, List<RecurringPatternResponse>>> list(
            @CurrentBusiness Long businessId,
            @RequestParam(required = false) PatternStatus status) {

        List<RecurringPattern> found = status == null
                ? repository.findByBusinessIdOrderByConfidenceDesc(businessId)
                : repository.findByBusinessIdAndStatusOrderByConfidenceDesc(businessId, status);

        var codes = categories.codeById();
        return ApiResponse.ok(Map.of("items", found.stream()
                .map(p -> RecurringPatternResponse.of(p, codes.get(p.getCategoryId())))
                .toList()));
    }

    @GetMapping("/recurring-patterns/{patternId}")
    @Transactional(readOnly = true)
    public ApiResponse<RecurringPatternResponse> get(@PathVariable Long patternId) {
        return ApiResponse.ok(toResponse(owned(patternId)));
    }

    /** 탐지 실행. 배치가 생기기 전까지의 임시 진입점이다. */
    @PostMapping("/businesses/{businessId}/recurring-patterns/detect")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    public ApiResponse<DetectionResult> detect(@CurrentBusiness Long businessId) {
        return ApiResponse.ok(new DetectionResult(
                service.detectAndUpsert(businessId, LocalDate.now(clock))));
    }

    @PostMapping("/recurring-patterns/{patternId}/confirm")
    @Transactional
    public ApiResponse<RecurringPatternResponse> confirm(@PathVariable Long patternId,
                                                         @Valid @RequestBody(required = false)
                                                         ConfirmRequest request) {
        owned(patternId);
        ConfirmRequest body = request == null
                ? new ConfirmRequest(null, null, null, null) : request;

        RecurringPattern pattern = service.confirm(patternId, guard.currentUserId(),
                resolveCategoryId(body.category()), body.amountMin(), body.amountMax(),
                body.holidayRule(), LocalDate.now(clock));

        return ApiResponse.ok(toResponse(pattern));
    }

    @PostMapping("/recurring-patterns/{patternId}/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void dismiss(@PathVariable Long patternId) {
        owned(patternId);
        service.dismiss(patternId);
    }

    @PatchMapping("/recurring-patterns/{patternId}")
    @Transactional
    public ApiResponse<RecurringPatternResponse> update(@PathVariable Long patternId,
                                                        @Valid @RequestBody UpdateRequest request) {
        owned(patternId);
        RecurringPattern pattern = service.update(patternId, resolveCategoryId(request.category()),
                request.amountMin(), request.amountMax(), request.holidayRule(),
                request.status(), LocalDate.now(clock));
        return ApiResponse.ok(toResponse(pattern));
    }

    /** 직접 경로라 {@code @CurrentBusiness} 를 안 거친다 — 소유권을 손으로 확인한다. */
    private RecurringPattern owned(Long patternId) {
        RecurringPattern pattern = service.find(patternId);
        guard.assertOwned(pattern);
        return pattern;
    }

    private RecurringPatternResponse toResponse(RecurringPattern pattern) {
        return RecurringPatternResponse.of(pattern, categories.codeById().get(pattern.getCategoryId()));
    }

    private Long resolveCategoryId(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return categories.idByCode(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "알 수 없는 카테고리입니다: " + code));
    }
}
