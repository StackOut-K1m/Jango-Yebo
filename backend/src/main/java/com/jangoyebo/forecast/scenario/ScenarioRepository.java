package com.jangoyebo.forecast.scenario;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScenarioRepository extends JpaRepository<Scenario, Long> {

    /**
     * 저장 목록 (11.2). 최신순이며 {@code ix_scenario_business} 가 그대로 받는다.
     *
     * <p>페이징을 두지 않는다 — 시나리오는 사용자가 손으로 만드는 것이라 수십 건을 넘지
     * 않고, 프론트 {@code SavedScenarioList} 도 {@code items} 만 읽는다.
     */
    List<Scenario> findByBusinessIdOrderByCreatedAtDesc(Long businessId);
}
