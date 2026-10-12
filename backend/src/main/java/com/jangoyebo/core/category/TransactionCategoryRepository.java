package com.jangoyebo.core.category;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public interface TransactionCategoryRepository extends JpaRepository<TransactionCategory, Long> {

    Optional<TransactionCategory> findByCodeAndActiveTrue(String code);

    List<TransactionCategory> findByActiveTrueOrderBySortOrderAsc();

    /**
     * id → code 매핑. 23행짜리 고정 시드라 목록 응답을 만들 때마다 조회해도 부담이 없다.
     * 부하가 보이면 캐시를 붙인다.
     */
    default Map<Long, String> codeById() {
        return findAll().stream().collect(
                Collectors.toMap(TransactionCategory::getId, TransactionCategory::getCode,
                        (a, b) -> a));
    }
}
