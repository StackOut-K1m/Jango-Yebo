package com.jangoyebo.core.report;

import com.jangoyebo.common.response.ApiResponse;
import com.jangoyebo.common.response.PageResponse;
import com.jangoyebo.common.tenancy.CurrentBusiness;
import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 주간·월간 AI 브리핑 (API 15.2 · P1-17).
 *
 * <p>생성은 배치가 한다 — 조회 경로는 만들지 않는다. 사용자가 부를 때 만들면 LLM 호출이
 * 화면 열 때마다 나가고, 무엇보다 브리핑은 「지난 기간 요약」이라 아무 때나 만들 것이 아니다.
 */
@RestController
@RequestMapping("/api/v1")
public class BriefingController {

    private final BriefingService service;

    public BriefingController(BriefingService service) {
        this.service = service;
    }

    @GetMapping("/businesses/{businessId}/briefings")
    public ApiResponse<PageResponse<BriefingSummary>> list(
            @CurrentBusiness Long businessId,
            @RequestParam(required = false) String periodType,
            @PageableDefault(size = 10) Pageable pageable) {
        return ApiResponse.ok(PageResponse.from(
                service.list(businessId, parseType(periodType), pageable)
                        .map(BriefingSummary::of)));
    }

    @GetMapping("/briefings/{briefingId}")
    public ApiResponse<BriefingDetail> get(@PathVariable Long briefingId) {
        return ApiResponse.ok(BriefingDetail.of(service.get(briefingId)));
    }

    private static ReportType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ReportType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "periodType 은 WEEKLY 또는 MONTHLY 입니다.");
        }
    }

    /** 목록 줄. 본문은 상세에서만 준다 — 목록에 문단을 실으면 응답이 길어지고 읽히지도 않는다. */
    public record BriefingSummary(Long briefingId, String periodType, LocalDate periodStart,
                                  LocalDate periodEnd, String headline, String status,
                                  Instant generatedAt) {

        static BriefingSummary of(Report report) {
            return new BriefingSummary(report.getId(), report.getReportType().name(),
                    report.getPeriodStart(), report.getPeriodEnd(), report.getHeadline(),
                    report.getStatus().name(), report.getGeneratedAt());
        }
    }

    /**
     * 상세.
     *
     * <p><b>{@code headline}·{@code content} 는 없을 수 있다.</b> 전역 {@code non_null} 정책이라
     * 문장이 아직 없거나 생성이 실패하면 <b>키가 통째로 빠진다</b> — 화면은 그때 지표만 그린다
     * (구현현황 3.3). 명세가 「생성 실패 시 지표만이라도 반환한다」고 정한 그 상태다.
     */
    public record BriefingDetail(Long briefingId, Long businessId, String periodType,
                                 LocalDate periodStart, LocalDate periodEnd, String headline,
                                 String content, List<Map<String, Object>> metrics, String status,
                                 Instant generatedAt) {

        static BriefingDetail of(Report report) {
            return new BriefingDetail(report.getId(), report.getBusinessId(),
                    report.getReportType().name(), report.getPeriodStart(), report.getPeriodEnd(),
                    report.getHeadline(), report.getNarrative(), report.getMetrics(),
                    report.getStatus().name(), report.getGeneratedAt());
        }
    }
}
