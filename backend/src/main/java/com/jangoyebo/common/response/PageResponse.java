package com.jangoyebo.common.response;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * 목록 응답 공통 구조 (API 명세 2.3).
 *
 * <p>Spring Data {@link Page}를 그대로 직렬화하면 {@code content}·{@code pageable}·{@code sort}가
 * 섞인 다른 모양이 나온다. 명세가 정한 형태로 변환해서 내보낸다.
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    /** 엔티티 페이지를 DTO로 바꾸면서 감싼다. */
    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return from(page.map(mapper));
    }
}
