package com.jangoyebo.forecast.scenario;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ScenarioAdjustmentRepository extends JpaRepository<ScenarioAdjustment, Long> {

    /** 저장한 순서 그대로. 파라미터 순서는 사용자가 조건을 쌓은 순서라 의미가 있다. */
    List<ScenarioAdjustment> findByScenarioIdOrderByIdAsc(Long scenarioId);

    /** 목록 조회의 N+1 을 막는다 — 시나리오마다 따로 읽지 않고 한 번에 가져와 나눈다. */
    List<ScenarioAdjustment> findByScenarioIdInOrderByIdAsc(Collection<Long> scenarioIds);
}
