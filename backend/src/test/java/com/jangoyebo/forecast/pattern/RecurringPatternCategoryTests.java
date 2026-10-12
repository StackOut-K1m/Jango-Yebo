package com.jangoyebo.forecast.pattern;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.forecast.pattern.RecurringPatternDetector.DetectedPattern;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 패턴이 분류를 어떻게 얻고 지키는가.
 *
 * <p><b>Docker 가 필요 없다.</b> 엔티티 메서드만 본다.
 *
 * <p>여기서 굳히는 것은 시나리오의 매출 변화가 서는 바닥이다 — 매출 변화는 예정 입금의
 * <b>분류</b>로 대상을 고르고(11.1), 그 분류는 거래 → 패턴 → 예정 거래로 복사돼 온다.
 * 패턴 칸이 비면 그 뒤가 전부 비고 화면은 「매출로 분류된 예정 입금이 없다」로 끝난다.
 */
class RecurringPatternCategoryTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 20);
    private static final Long CARD_SETTLEMENT = 1L;
    private static final Long PLATFORM_SETTLEMENT = 2L;

    /**
     * <b>분류보다 먼저 잡힌 후보가 재탐지로 분류를 얻어야 한다.</b>
     *
     * <p>분류 Job 은 수집 직후 비동기로 돌고 탐지는 사용자가 누르므로 순서가 뒤집힌다.
     * 여기서 채우지 않으면 승인 화면에도 분류 칸이 없어 <b>채울 길이 아예 없다.</b>
     */
    @Test
    void 재탐지가_비어_있던_분류를_채운다() {
        RecurringPattern pattern = RecurringPattern.candidate(7L, detected(null));

        pattern.refreshIfCandidate(detected(CARD_SETTLEMENT));

        assertThat(pattern.getCategoryId()).isEqualTo(CARD_SETTLEMENT);
    }

    /**
     * 표본이 아직 미분류인 것과 분류가 풀린 것은 다르다. 빈 결과로 있던 값을 지우면
     * 분류를 마친 패턴이 <b>다음 재탐지마다 매출 대상에서 빠졌다 들어왔다</b> 한다.
     */
    @Test
    void 재탐지가_빈_결과로_있던_분류를_지우지_않는다() {
        RecurringPattern pattern = RecurringPattern.candidate(7L, detected(CARD_SETTLEMENT));

        pattern.refreshIfCandidate(detected(null));

        assertThat(pattern.getCategoryId()).isEqualTo(CARD_SETTLEMENT);
    }

    /** 승인·무시된 것은 갱신 대상이 아니다 — 분류도 예외가 아니다(사용자가 고른 값이다). */
    @Test
    void 확정된_패턴은_재탐지가_분류도_건드리지_않는다() {
        RecurringPattern pattern = RecurringPattern.candidate(7L, detected(CARD_SETTLEMENT));
        pattern.confirm(1L, null, null, null, null);

        pattern.refreshIfCandidate(detected(PLATFORM_SETTLEMENT));

        assertThat(pattern.getCategoryId()).isEqualTo(CARD_SETTLEMENT);
    }

    /** 백필은 빈칸만 메운다. 사용자가 고른 분류를 거래 쪽 사정으로 뒤엎지 않는다. */
    @Test
    void 백필은_있는_분류를_덮지_않는다() {
        RecurringPattern pattern = RecurringPattern.candidate(7L, detected(CARD_SETTLEMENT));

        assertThat(pattern.fillCategoryIfBlank(PLATFORM_SETTLEMENT)).isFalse();
        assertThat(pattern.getCategoryId()).isEqualTo(CARD_SETTLEMENT);
    }

    /** 채울 값이 없으면 채웠다고 하지 않는다 — 호출자가 예정 거래를 괜히 다시 만든다. */
    @Test
    void 백필은_빈_분류를_채우고_채운_경우만_참이다() {
        RecurringPattern pattern = RecurringPattern.candidate(7L, detected(null));

        assertThat(pattern.fillCategoryIfBlank(null)).isFalse();
        assertThat(pattern.fillCategoryIfBlank(CARD_SETTLEMENT)).isTrue();
        assertThat(pattern.getCategoryId()).isEqualTo(CARD_SETTLEMENT);
        assertThat(pattern.fillCategoryIfBlank(PLATFORM_SETTLEMENT)).isFalse();
    }

    private static DetectedPattern detected(Long categoryId) {
        return new DetectedPattern(11L, categoryId, FlowType.DEPOSIT, "카드 정산",
                CadenceType.WEEKLY, null, null, 3, HolidayRule.NEXT_BUSINESS_DAY,
                new BigDecimal("800000"), new BigDecimal("1200000"), null,
                TODAY.plusDays(2), new BigDecimal("0.82"), List.of(41L, 42L, 43L));
    }
}
