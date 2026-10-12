package com.jangoyebo.core.category;

import com.jangoyebo.core.CategoryCatalog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/** {@link CategoryCatalog} 구현. Repository 는 {@code core} 안에만 머무른다. */
@Service
public class CategoryCatalogService implements CategoryCatalog {

    private final TransactionCategoryRepository repository;

    public CategoryCatalogService(TransactionCategoryRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> idByCode(String code) {
        return repository.findByCodeAndActiveTrue(code).map(TransactionCategory::getId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, String> codeById() {
        return repository.codeById();
    }
}
