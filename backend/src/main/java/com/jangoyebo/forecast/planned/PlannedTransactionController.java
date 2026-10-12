package com.jangoyebo.forecast.planned;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.response.ApiResponse;
import com.jangoyebo.common.response.PageResponse;
import com.jangoyebo.common.tenancy.BusinessGuard;
import com.jangoyebo.common.tenancy.CurrentBusiness;
import com.jangoyebo.core.CategoryCatalog;
import com.jangoyebo.forecast.run.ForecastInputsChanged;
import com.jangoyebo.forecast.planned.PlannedTransactionDtos.CreateRequest;
import com.jangoyebo.forecast.planned.PlannedTransactionDtos.PlannedTransactionResponse;
import com.jangoyebo.forecast.planned.PlannedTransactionDtos.UpdateRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 예정 거래 (API 9.4, P0-06).
 *
 * <p><b>잔액 예측(P0-07)의 입력을 만드는 자리다.</b> 곡선 계산 자체는 여기 없다 —
 * 이 목록을 날짜별로 더하는 것은 예측 엔진의 일이다.
 *
 * <p>{@code POST /planned-transactions/{id}/match}(P1-05)는 열지 않았다. 실제 거래와의
 * 매칭이라 거래 쪽 로직이 함께 필요하다.
 */
@RestController
@RequestMapping("/api/v1")
public class PlannedTransactionController {

    /**
     * 조회 범위 상한 (API 9.4).
     *
     * <p>패턴 승인 시 서버가 향후 90일치를 만든다. 92일이면 그 전량에 월 경계 여유를 더한
     * 값이라, 화면이 한 번에 요구할 수 있는 최대치와 저장되는 최대치가 맞는다.
     */
    private static final long MAX_RANGE_DAYS = 92;

    /**
     * 「오늘」을 정하는 시간대.
     *
     * <p>앱 공용 {@code Clock} 은 {@code systemDefaultZone()} 이라 컨테이너 TZ 에 의존한다.
     * 예정일은 사업장의 벽시계 날짜로 판정해야 하므로 {@code ForecastRunWorker} 처럼
     * 여기서 KST 를 명시한다 — 기준일을 정하는 쪽과 같은 날이어야 한다.
     */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final PlannedTransactionRepository repository;
    private final PlannedExecutionRepository executions;
    private final CategoryCatalog categories;
    private final BusinessGuard guard;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public PlannedTransactionController(PlannedTransactionRepository repository,
                                        PlannedExecutionRepository executions,
                                        CategoryCatalog categories,
                                        BusinessGuard guard,
                                        ApplicationEventPublisher events,
                                        Clock clock) {
        this.repository = repository;
        this.executions = executions;
        this.categories = categories;
        this.guard = guard;
        this.events = events;
        this.clock = clock;
    }

    /**
     * 지난 날짜는 받지 않는다.
     *
     * <p>예측은 기준일 다음 날부터 그리고 당일자는 첫 점이 받는다. <b>그보다 이전은 곡선이
     * 닿지 않는 구간</b>이라 저장해도 어디에도 나타나지 않는다 — 201 을 돌려주고 조용히
     * 사라지는 것보다 여기서 막는 편이 낫다. 이미 지난 지출은 예정 거래가 아니라 거래다.
     */
    private void requireNotPast(LocalDate expectedDate) {
        if (expectedDate == null) {
            return;
        }
        LocalDate today = LocalDate.now(clock.withZone(KST));
        if (expectedDate.isBefore(today)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "예정일은 오늘 이후여야 합니다.");
        }
    }

    @GetMapping("/businesses/{businessId}/planned-transactions")
    @Transactional(readOnly = true)
    public ApiResponse<PageResponse<PlannedTransactionResponse>> list(
            @CurrentBusiness Long businessId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) PlannedStatus status,
            @RequestParam(required = false) PlannedSourceType source,
            @RequestParam(required = false) FlowType direction,
            @PageableDefault(size = 20, sort = "expectedDate", direction = Sort.Direction.ASC)
            Pageable pageable) {

        assertRange(from, to);

        Page<PlannedTransaction> page = repository.search(
                businessId, from, to, status, source, direction, pageable);

        var codes = categories.codeById();
        return ApiResponse.ok(PageResponse.from(page,
                p -> PlannedTransactionResponse.of(p, codes.get(p.getCategoryId()))));
    }

    @PostMapping("/businesses/{businessId}/planned-transactions")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ApiResponse<PlannedTransactionResponse> create(@CurrentBusiness Long businessId,
                                                          @Valid @RequestBody CreateRequest request) {
        if (!request.hasExactlyOneAmountForm()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "금액은 amount 또는 amountMin·amountMax 중 한 형태로 보냅니다.");
        }
        if (request.amountMin() != null && request.amountMin().compareTo(request.amountMax()) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "amountMin 이 amountMax 보다 큽니다.");
        }
        requireNotPast(request.expectedDate());

        PlannedTransaction planned = PlannedTransaction.byUser(
                businessId, guard.currentUserId(), request.title(), request.expectedDate(),
                request.direction(), request.min(), request.expected(), request.max(),
                request.accountId(), resolveCategoryId(request.category()), request.memo());

        PlannedTransaction saved = repository.save(planned);
        plannedChanged(businessId);
        return ApiResponse.ok(PlannedTransactionResponse.of(saved, request.category()));
    }

    @PatchMapping("/planned-transactions/{plannedTransactionId}")
    @Transactional
    public ApiResponse<PlannedTransactionResponse> update(@PathVariable Long plannedTransactionId,
                                                          @Valid @RequestBody UpdateRequest request) {
        if (!request.hasValidAmountForm()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "금액은 amount 또는 amountMin·amountMax 중 한 형태로 보냅니다.");
        }

        requireNotPast(request.expectedDate());

        PlannedTransaction planned = find(plannedTransactionId);
        assertNotExecuted(planned, request);
        Long categoryId = resolveCategoryId(request.category());

        planned.update(request.title(), categoryId, request.expectedDate(),
                request.amountMin() != null ? request.amountMin() : request.amount(),
                request.amount() != null ? request.amount() : midpoint(request),
                request.amountMax() != null ? request.amountMax() : request.amount(),
                request.memo());

        // 명세가 허용하는 상태 전이는 취소뿐이다 — 나머지는 매칭 배치와 패턴이 정한다.
        if (request.status() == PlannedStatus.CANCELLED) {
            planned.cancel();
        } else if (request.status() != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "이 API 로 바꿀 수 있는 상태는 CANCELLED 뿐입니다.");
        }

        plannedChanged(planned.getBusinessId());

        var codes = categories.codeById();
        return ApiResponse.ok(PlannedTransactionResponse.of(planned, codes.get(planned.getCategoryId())));
    }

    /**
     * 삭제는 사용자가 만든 것만 허용한다.
     *
     * <p>패턴 생성분을 지우면 다음 롤링 배치가 같은 것을 다시 만들어 <b>취소한 것이 되살아난다.</b>
     * 그래서 {@code 409 PLANNED_TX_NOT_DELETABLE} 로 막고 {@code PATCH ... status=CANCELLED} 로 보낸다.
     */
    @DeleteMapping("/planned-transactions/{plannedTransactionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable Long plannedTransactionId) {
        PlannedTransaction planned = find(plannedTransactionId);
        if (!planned.isDeletable()) {
            throw new BusinessException(ErrorCode.PLANNED_TX_NOT_DELETABLE);
        }
        if (executions.existsByPlannedTransactionId(plannedTransactionId)) {
            // 이미 금융망에 나간 돈이다. 지우면 실행 원장이 가리킬 곳이 사라지고
            // (외래키가 막는다) 무엇이 왜 나갔는지 설명할 근거도 없어진다.
            throw new BusinessException(ErrorCode.PLANNED_TX_NOT_DELETABLE,
                    "이미 금융망에 반영된 예정 거래는 삭제할 수 없습니다.");
        }
        repository.delete(planned);
        plannedChanged(planned.getBusinessId());
    }

    /**
     * 예정 거래가 바뀌면 곡선도 바뀐다 — 커밋 뒤 재계산을 예약한다(공통규약 2.6).
     *
     * <p>워커를 직접 부르지 않고 이벤트로 도는 이유는 <b>여기가 아직 커밋 전</b>이기 때문이다.
     * 바로 부르면 저장되지 않은 예정 거래를 읽어 곡선을 만들 수 있다.
     */
    private void plannedChanged(Long businessId) {
        events.publishEvent(ForecastInputsChanged.of(
                businessId, ForecastInputsChanged.Reason.PLANNED_CHANGED));
    }

    /**
     * 이미 금융망에 반영된 예정 거래는 <b>돈에 닿는 값을 바꿀 수 없다</b> (API 9.6).
     *
     * <p>날짜·금액을 고치거나 취소해도 나간 돈은 돌아오지 않는다. 그대로 두면 화면의 계획과
     * 통장이 갈리고, 정확 매칭도 「예정일과 며칠 차이」를 엉뚱하게 적는다. 이름·메모·분류는
     * 표시일 뿐이라 그대로 열어 둔다.
     */
    private void assertNotExecuted(PlannedTransaction planned, UpdateRequest request) {
        boolean touchesMoney = request.expectedDate() != null || request.amount() != null
                || request.amountMin() != null || request.amountMax() != null
                || request.status() == PlannedStatus.CANCELLED;
        if (!touchesMoney) {
            return;
        }
        executions.findByPlannedTransactionId(planned.getId())
                .filter(execution -> execution.getStatus() != PlannedExecutionStatus.FAILED)
                .ifPresent(execution -> {
                    throw new BusinessException(ErrorCode.PLANNED_TX_NOT_DELETABLE,
                            "이미 금융망에 반영된 예정 거래는 날짜·금액을 바꾸거나 취소할 수 없습니다.");
                });
    }

    private PlannedTransaction find(Long plannedTransactionId) {
        PlannedTransaction planned = repository.findById(plannedTransactionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANNED_TX_NOT_FOUND));
        // 직접 경로다 — @CurrentBusiness 를 거치지 않으므로 명시 검증이 필요하다.
        guard.assertOwned(planned);
        return planned;
    }

    private Long resolveCategoryId(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return categories.idByCode(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "알 수 없는 카테고리입니다: " + code));
    }

    private static java.math.BigDecimal midpoint(UpdateRequest request) {
        if (request.amountMin() == null || request.amountMax() == null) {
            return null;
        }
        return request.amountMin().add(request.amountMax())
                .divide(java.math.BigDecimal.valueOf(2), 4, java.math.RoundingMode.HALF_UP);
    }

    /**
     * 범위를 안 받으면 롤링 배치가 만든 90일치가 통째로 나온다. 명세가 상한을 정한 이유다.
     */
    private static void assertRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "from 이 to 보다 늦습니다.");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "조회 범위는 최대 " + MAX_RANGE_DAYS + "일입니다.");
        }
    }
}
