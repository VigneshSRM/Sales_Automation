package com.infeara.sales.common.paging;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pagination, sorting and filtering request parameters with hard server-side limits
 * (NFR-PERF-001/002). Client-provided sort expressions never reach SQL: only
 * allowlisted field names are mapped to entity paths.
 */
public record PageQuery(int page, int size, String sortBy, boolean descending,
                        Map<String, String> filters, List<String> appliedFilters) {

    public static final int DEFAULT_SIZE = 25;
    public static final int MAX_SIZE = 100;
    public static final int MAX_PAGE = 10_000;

    public static PageQuery of(Integer page, Integer size, String sort, String direction,
                               Map<String, String> filters, Set<String> allowedFilters) {
        int p = page == null || page < 0 ? 0 : Math.min(page, MAX_PAGE);
        int s = size == null || size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        String sortBy = sort == null || sort.isBlank() ? null : sort;
        boolean desc = direction == null || !"asc".equalsIgnoreCase(direction);
        Map<String, String> cleaned = filters == null ? Map.of() : filters;
        List<String> applied = cleaned.entrySet().stream()
                .filter(e -> allowedFilters == null || allowedFilters.contains(e.getKey()))
                .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                .map(e -> e.getKey() + "=" + e.getValue())
                .sorted()
                .toList();
        return new PageQuery(p, s, sortBy, desc, cleaned, applied);
    }

    /** Validates an allowlisted sort field and returns the JPA property path. */
    public String resolveSort(Map<String, String> sortableFields, String fallback) {
        if (sortBy == null) {
            return fallback;
        }
        String mapped = sortableFields.get(sortBy);
        if (mapped == null) {
            throw new com.infeara.sales.common.error.ApiException(
                    com.infeara.sales.common.error.ErrorCode.INVALID_SORT_FIELD,
                    "Sorting by '" + sortBy + "' is not supported");
        }
        return mapped;
    }

    public int offset() {
        return page * size;
    }
}
