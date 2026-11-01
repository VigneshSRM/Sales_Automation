package com.infeara.sales.common.api;

import java.util.List;

/** Server-side pagination envelope. Page size is capped by {@code PageQuery}. */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages,
                              String sort, List<String> appliedFilters) {

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long total, String sort,
                                         List<String> appliedFilters) {
        int pages = size == 0 ? 0 : (int) Math.ceil((double) total / size);
        return new PageResponse<>(items, page, size, total, pages, sort, appliedFilters);
    }
}
