package com.jangoyebo.core.settlement;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.response.ApiResponse;
import com.jangoyebo.common.tenancy.BusinessGuard;
import com.jangoyebo.common.tenancy.CurrentBusiness;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 정산 수동 대사 (API 14.1, P0-11). */
@RestController
@RequestMapping("/api/v1")
public class SettlementController {

    private final SettlementRepository repository;
    private final SettlementService service;
    private final BusinessGuard guard;

    public SettlementController(SettlementRepository repository,
                                SettlementService service,
                                BusinessGuard guard) {
        this.repository = repository;
        this.service = service;
        this.guard = guard;
    }

    @GetMapping("/businesses/{businessId}/settlements")
    @Transactional(readOnly = true)
    public ApiResponse<Map<String, List<SettlementResponse>>> list(@CurrentBusiness Long businessId) {
        return ApiResponse.ok(Map.of("items",
                repository.findByBusinessIdOrderBySalesToDesc(businessId).stream()
                        .map(this::toResponse).toList()));
    }

    @PostMapping("/businesses/{businessId}/settlements")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ApiResponse<SettlementResponse> create(@CurrentBusiness Long businessId,
                                                  @Valid @RequestBody CreateRequest request) {
        Settlement settlement = service.create(businessId, request.platformName(),
                request.periodStart(), request.periodEnd(), request.grossSales(),
                nullToZero(request.cancellations()), nullToZero(request.fees()),
                request.matchedTransactionIds());
        return ApiResponse.ok(toResponse(settlement));
    }

    @GetMapping("/settlements/{settlementId}")
    @Transactional(readOnly = true)
    public ApiResponse<SettlementResponse> get(@PathVariable Long settlementId) {
        return ApiResponse.ok(toResponse(owned(settlementId)));
    }

    @PatchMapping("/settlements/{settlementId}")
    @Transactional
    public ApiResponse<SettlementResponse> update(@PathVariable Long settlementId,
                                                  @Valid @RequestBody UpdateRequest request) {
        Settlement settlement = service.update(owned(settlementId), request.platformName(),
                request.periodStart(), request.periodEnd(), request.grossSales(),
                request.cancellations(), request.fees(), request.matchedTransactionIds());
        return ApiResponse.ok(toResponse(settlement));
    }

    @DeleteMapping("/settlements/{settlementId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable Long settlementId) {
        service.delete(owned(settlementId));
    }

    /** 직접 경로다 — {@code @CurrentBusiness} 를 안 거치므로 소유권을 손으로 본다. */
    private Settlement owned(Long settlementId) {
        Settlement settlement = repository.findById(settlementId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SETTLEMENT_NOT_FOUND));
        guard.assertOwned(settlement);
        return settlement;
    }

    private SettlementResponse toResponse(Settlement s) {
        return SettlementResponse.of(s, service.actualDeposit(s.getId()));
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * @param cancellations 생략하면 0. 취소가 없는 정산이 흔하다
     * @param fees          생략하면 0
     */
    public record CreateRequest(
            @NotBlank @Size(max = 50) String platformName,
            @NotNull LocalDate periodStart,
            @NotNull LocalDate periodEnd,
            @NotNull @PositiveOrZero BigDecimal grossSales,
            @PositiveOrZero BigDecimal cancellations,
            @PositiveOrZero BigDecimal fees,
            List<Long> matchedTransactionIds) {
    }

    /** 전달된 필드만 바뀐다. {@code matchedTransactionIds} 는 <b>전량 교체</b>다. */
    public record UpdateRequest(
            @Size(max = 50) String platformName,
            LocalDate periodStart,
            LocalDate periodEnd,
            @PositiveOrZero BigDecimal grossSales,
            @PositiveOrZero BigDecimal cancellations,
            @PositiveOrZero BigDecimal fees,
            List<Long> matchedTransactionIds) {
    }

    /**
     * {@code expectedPayout}·{@code difference} 는 저장하지 않고 매번 계산한다 —
     * 총매출·취소·수수료에서 결정되는 파생값이라 저장하면 원본과 어긋날 수 있다(ERD 주석).
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SettlementResponse(
            Long settlementId, String platformName,
            LocalDate periodStart, LocalDate periodEnd,
            BigDecimal grossSales, BigDecimal cancellations, BigDecimal fees,
            BigDecimal expectedPayout, BigDecimal actualDeposit, BigDecimal difference,
            SettlementStatus status, Instant createdAt) {

        static SettlementResponse of(Settlement s, BigDecimal actualDeposit) {
            return new SettlementResponse(
                    s.getId(), s.getPlatformName(), s.getSalesFrom(), s.getSalesTo(),
                    s.getGrossSales(), s.getCancellationAmount(), s.getFeeAmount(),
                    s.expectedPayout(), actualDeposit, s.difference(actualDeposit),
                    s.getStatus(), s.getCreatedAt());
        }
    }
}
