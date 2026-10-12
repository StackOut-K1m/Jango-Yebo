package com.jangoyebo.forecast.run;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DailyBalanceForecastRepository extends JpaRepository<DailyBalanceForecast, Long> {

    /** 곡선 한 벌. 날짜 오름차순이라 조회에서 앞에서부터 {@code days} 만큼 자르면 된다. */
    List<DailyBalanceForecast> findByForecastRunIdOrderByForecastDateAsc(Long forecastRunId);
}
