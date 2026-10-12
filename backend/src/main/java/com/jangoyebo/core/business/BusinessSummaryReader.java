package com.jangoyebo.core.business;

import com.jangoyebo.common.domain.RiskLevel;
import com.jangoyebo.core.SafetyLineOutlook;
import com.jangoyebo.core.account.AccountRepository;
import com.jangoyebo.core.safety.SafetyBalancePolicyRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 사업장 목록·상세의 파생 필드 (API 7.1 {@code accountCount}·{@code currentRiskLevel}).
 *
 * <p><b>둘 다 스텁이었다.</b> {@code accountCount} 는 리터럴 {@code 0} 이었고
 * ("계좌 연동 착수 시 실제 개수로 대체" — 계좌 연동은 한참 전에 끝났다),
 * {@code currentRiskLevel} 은 언제나 {@code null} 이었다. 로그인 직후 첫 화면이 읽는 값인데
 * 예측 곡선과 안전선이 다 선 지금은 둘 다 셀 수 있다.
 *
 * <p><b>등급은 안전선 화면과 같은 출처를 쓴다.</b> {@link SafetyLineOutlook} 이 저장된 곡선에서
 * 뽑는 그 값이다 — 여기서 따로 계산하면 목록의 배지와 상세 화면의 등급이 어긋난다.
 */
@Component
class BusinessSummaryReader {

    private final AccountRepository accounts;
    private final SafetyBalancePolicyRepository policies;
    private final SafetyLineOutlook outlook;

    BusinessSummaryReader(AccountRepository accounts, SafetyBalancePolicyRepository policies,
                          SafetyLineOutlook outlook) {
        this.accounts = accounts;
        this.policies = policies;
        this.outlook = outlook;
    }

    long connectedAccountCount(Long businessId) {
        return accounts.countByBusinessIdAndStatus(businessId, "CONNECTED");
    }

    /**
     * 현재 잔액의 위험 등급.
     *
     * <p><b>예측 실행이 없으면 {@code null} 이다.</b> 여기서 예측을 돌리지 않는다 — 조회가
     * 계산을 부르면 공통규약 2.6 이 막은 경로가 된다. 화면은 이미 「미설정 시 배너 숨김」으로
     * 설계돼 있다(§3.6).
     *
     * <p>안전선이 없어도 등급은 나온다 — 0원 기준 판정이 남아 있어서다.
     */
    String currentRiskLevel(Long businessId) {
        BigDecimal safetyLine = policies.findByBusinessId(businessId)
                .map(policy -> policy.getAmount())
                .orElse(null);
        return outlook.of(businessId, safetyLine)
                .map(SafetyLineOutlook.Current::riskLevel)
                .map(RiskLevel::name)
                .orElse(null);
    }
}
