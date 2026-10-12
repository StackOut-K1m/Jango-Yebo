package com.jangoyebo.core.category;

import com.jangoyebo.common.response.ApiResponse;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 거래 카테고리 목록 (API 8.1). 분류 UI가 선택지를 채우는 데 쓴다. */
@RestController
@RequestMapping("/api/v1/transaction-categories")
public class CategoryController {

    private final TransactionCategoryRepository repository;

    public CategoryController(TransactionCategoryRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ApiResponse<Map<String, List<CategoryResponse>>> list() {
        List<CategoryResponse> items = repository.findByActiveTrueOrderBySortOrderAsc()
                .stream()
                .map(c -> new CategoryResponse(c.getCode(), c.getName(), c.getFlowType()))
                .toList();
        return ApiResponse.ok(Map.of("items", items));
    }

    public record CategoryResponse(String code, String name, String direction) {
    }
}
