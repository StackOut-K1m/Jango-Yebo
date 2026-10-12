package com.jangoyebo.core.tax;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 신고서 초안 (API 14.3). */
public interface TaxFilingDraftRepository extends JpaRepository<TaxFilingDraft, Long> {

    /**
     * 같은 서식·귀속연도로 아직 집계 중인 초안.
     *
     * <p>버튼을 두 번 눌러도 초안이 둘 생기지 않게 한다 — 8.5 분류가 같은 판단을 했다
     * ({@code JobService.enqueueOrCurrent}). 완료된 초안은 대상이 아니다: 재생성은
     * 새 행을 만드는 것이 맞다(ERD — 이력).
     */
    Optional<TaxFilingDraft> findFirstByBusinessIdAndTaxYearAndTemplateIdAndStatusOrderByIdDesc(
            Long businessId, int taxYear, Long templateId, TaxDraftStatus status);

    /**
     * 같은 서식·귀속연도의 직전 초안.
     *
     * <p>재생성이 사용자 수정값을 물려받는 데 쓴다. 이력을 위해 행은 새로 만들지만
     * <b>사장님이 적은 값까지 새로 시작하면 재생성이 「작업을 다시 하라」가 된다</b>.
     */
    Optional<TaxFilingDraft> findFirstByBusinessIdAndTaxYearAndTemplateIdOrderByIdDesc(
            Long businessId, int taxYear, Long templateId);
}
