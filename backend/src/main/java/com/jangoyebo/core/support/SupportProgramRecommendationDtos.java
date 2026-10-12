package com.jangoyebo.core.support;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 맞춤·공백 연계 추천 응답 (17.3). */
public final class SupportProgramRecommendationDtos {

    /** 17.1 이 고정 노출로 못박은 문구. 화면이 지우지 못하도록 응답에 싣는다. */
    public static final String DISCLAIMER = "자격 충족 여부는 주관 기관 확인이 필요합니다.";

    private SupportProgramRecommendationDtos() {
    }

    /**
     * 추천 묶음.
     *
     * @param cashGap 완료된 예측이 없거나 공백이 없으면 {@code null} — 응답 규약이
     *                {@code non_null} 이라 키 자체가 빠진다. <b>공백을 몰라도 추천은 낸다</b>
     */
    public record Recommendations(Instant baseTime,
                                  Profile profile,
                                  CashGap cashGap,
                                  List<Item> items,
                                  String disclaimer) {
    }

    /** 점수의 입력. 화면이 「무엇을 기준으로 골랐는지」를 보여 준다(도메인 규칙 1 — 근거). */
    public record Profile(String region,
                          String industryType,
                          BigDecimal annualRevenue,
                          Integer yearsInBusiness) {
    }

    public record CashGap(LocalDate firstShortfallDate, BigDecimal maxShortfallAmount) {
    }

    /**
     * 추천 한 줄.
     *
     * @param matchedConditions 맞은 조건의 이름. <b>점수보다 이쪽이 중요하다</b> — 0.91 이
     *                          무슨 뜻인지는 아무도 모르지만 「지역·업종·매출 상한」은 읽힌다
     * @param canArriveBeforeGap <b>{@code expectedDisbursementDays} 가 있을 때만 채운다.</b>
     *                           실측상 공고의 97% 가 지급 시기를 말하지 않는다 — 모르는 것을
     *                           {@code false} 로 두면 「못 받는다」로 읽히고, {@code true} 로
     *                           두면 오지 않을 돈을 기다리게 된다. 그래서 키를 뺀다
     * @param timingNote         시점에 대해 말할 수 있는 한 줄. 모를 때도 비우지 않는다 —
     *                           <b>빈 자리는 화면에서 「문제 없음」으로 읽힌다</b>
     */
    public record Item(Long programId,
                       String title,
                       String organizer,
                       String sourceUrl,
                       String supportType,
                       BigDecimal maxAmount,
                       LocalDate applyEnd,
                       double matchScore,
                       List<String> matchedConditions,
                       boolean needsVerification,
                       Integer expectedDisbursementDays,
                       Boolean canArriveBeforeGap,
                       String timingNote) {
    }
}
