package com.jangoyebo.core.business;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 사업장 API의 요청·응답 (API 명세 7.1). */
public final class BusinessDtos {

    private BusinessDtos() {
    }

    private static final String INDUSTRY_TYPES =
            "^(FOOD_CAFE|FOOD_RESTAURANT|FOOD_BAKERY|FOOD_OTHER|RETAIL|SERVICE|OTHER)$";
    private static final String TAXATION_TYPES = "^(GENERAL|SIMPLIFIED)$";

    public record CreateRequest(
            @NotBlank @Size(max = 100) String name,
            @Pattern(regexp = "^[0-9]{3}-[0-9]{2}-[0-9]{5}$", message = "사업자등록번호 형식이 올바르지 않습니다.")
            String businessRegNo,
            @Pattern(regexp = INDUSTRY_TYPES) String industryType,
            @Size(max = 50) String region,
            @NotBlank @Pattern(regexp = TAXATION_TYPES) String taxationType,
            LocalDate openedAt,
            @PositiveOrZero BigDecimal annualRevenue,
            @PositiveOrZero Integer employeeCount) {
    }

    public record UpdateRequest(
            @Size(max = 100) String name,
            @Pattern(regexp = "^[0-9]{3}-[0-9]{2}-[0-9]{5}$") String businessRegNo,
            @Pattern(regexp = INDUSTRY_TYPES) String industryType,
            @Size(max = 50) String region,
            @Pattern(regexp = TAXATION_TYPES) String taxationType,
            LocalDate openedAt,
            @PositiveOrZero BigDecimal annualRevenue,
            @PositiveOrZero Integer employeeCount) {
    }

    /**
     * @param accountCount     <b>연결된</b> 계좌 수. 연동을 끊은 계좌는 빠진다 — 잔액 합계가
     *                         세는 기준과 같아야 화면이 「계좌 3개」라 하고 곡선이 2개로 도는
     *                         일이 없다(§3.3)
     * @param currentRiskLevel 저장된 예측 곡선과 안전선에서 뽑은 <b>현재 잔액</b>의 등급.
     *                         예측 실행이 아직 없으면 {@code null} 이고, 화면은 그때 위험
     *                         배너를 숨긴다
     */
    public record BusinessResponse(
            Long businessId, String name, String businessRegNoMasked, String industryType,
            String region, String taxationType, LocalDate openedAt,
            BigDecimal annualRevenue, Integer employeeCount,
            long accountCount, String currentRiskLevel) {

        /**
         * 사업자등록번호는 응답에 <b>저장된 마스킹 값</b>만 나간다(API 21장 개인정보 보호).
         *
         * <p>여기서 마스킹을 계산하지 않는다. 엔티티가 들고 있는 것은 암호문이고,
         * 그것에 {@code substring} 을 걸면 암호문 조각이 그대로 나간다 — 실제로 그랬다.
         * 마스킹은 쓰기 시점에 평문으로 계산해 저장한다({@code BusinessController}).
         */
        public static BusinessResponse of(Business b, long accountCount, String currentRiskLevel) {
            return new BusinessResponse(
                    b.getId(), b.getName(), b.getRegistrationNumberMasked(), b.getIndustryCode(),
                    b.getRegion(), b.getTaxationType(), b.getOpenedOn(),
                    b.getAnnualRevenue(), b.getEmployeeCount(), accountCount, currentRiskLevel);
        }
    }
}
