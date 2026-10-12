package com.jangoyebo.core.report;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.response.ApiResponse;
import com.jangoyebo.common.tenancy.CurrentBusiness;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 월별 지출·수익 리포트 (API 15.3 · P1-19).
 *
 * <p>조회 때 계산한다 — 마감되지 않은 달은 매일 값이 달라지므로 스냅샷으로 둘 수 없다.
 */
@RestController
@RequestMapping("/api/v1")
public class MonthlyReportController {

    private final MonthlyReportService service;

    public MonthlyReportController(MonthlyReportService service) {
        this.service = service;
    }

    /**
     * @param month {@code 2026-08}. 경로 변수라 형식이 틀리면 {@code 400} 이다 — 500 으로
     *              새어 나가면 화면이 「서버 오류」로 읽고 사용자는 고칠 방법을 모른다
     */
    @GetMapping("/businesses/{businessId}/monthly-reports/{month}")
    public ApiResponse<Map<String, Object>> get(@CurrentBusiness Long businessId,
                                                @PathVariable String month) {
        return ApiResponse.ok(service.of(businessId, parse(month)));
    }

    private static YearMonth parse(String month) {
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "월은 2026-08 형식입니다.");
        }
    }
}
