package com.jangoyebo.core.productsales;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProductSalesExtractionRepository
        extends JpaRepository<ProductSalesExtraction, Long> {

    /** 같은 Job 의 콜백이 두 번 오면 두 행이 되지 않게 하는 판정. */
    Optional<ProductSalesExtraction> findByJobId(Long jobId);
}
