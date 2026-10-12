package com.jangoyebo.core.safety;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SafetyBalancePolicyRepository extends JpaRepository<SafetyBalancePolicy, Long> {

    /** 사업장당 한 행이다({@code uk_sbp_business}). */
    Optional<SafetyBalancePolicy> findByBusinessId(Long businessId);
}
