package com.jangoyebo.core.safety;

import com.jangoyebo.core.SafetyLines;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * {@link SafetyLines} 구현. 사업장당 한 행이라({@code uk_sbp_business}) 조회가 단순하다.
 */
@Service
public class SafetyLinesService implements SafetyLines {

    private final SafetyBalancePolicyRepository repository;

    public SafetyLinesService(SafetyBalancePolicyRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BigDecimal> currentAmount(Long businessId) {
        return repository.findByBusinessId(businessId).map(SafetyBalancePolicy::getAmount);
    }
}
