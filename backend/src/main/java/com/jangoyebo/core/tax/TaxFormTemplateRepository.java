package com.jangoyebo.core.tax;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 서식 템플릿 조회 (API 14.3). 기준 데이터라 사업장 필터가 없다. */
public interface TaxFormTemplateRepository extends JpaRepository<TaxFormTemplate, Long> {

    /** 귀속연도별 서식 목록. 정렬은 id 순 — 시드가 지원 수준이 높은 것부터 넣는다. */
    List<TaxFormTemplate> findByTaxYearAndActiveTrueOrderByIdAsc(int taxYear);

    /** 초안 생성이 쓴다 (다음 슬라이스). */
    Optional<TaxFormTemplate> findByFormTypeAndTaxYearAndActiveTrue(String formType, int taxYear);
}
