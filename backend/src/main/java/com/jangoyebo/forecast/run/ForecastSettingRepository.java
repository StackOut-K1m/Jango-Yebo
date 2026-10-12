package com.jangoyebo.forecast.run;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ForecastSettingRepository extends JpaRepository<ForecastSetting, Long> {

    /** 사업장당 한 행({@code uk_fs_business}). 비어 있는 것이 정상이다. */
    Optional<ForecastSetting> findByBusinessId(Long businessId);
}
