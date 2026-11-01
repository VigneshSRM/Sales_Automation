package com.infeara.sales.common.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.common.error.ErrorCode;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** NFR-PERF-001: the client can never ask for an unbounded page, and cannot inject sort SQL. */
class PageQueryTest {

    @Test
    @DisplayName("clamps page and size to server-side limits")
    void clampsLimits() {
        PageQuery huge = PageQuery.of(99_999, 5_000, null, null, Map.of(), Set.of());
        assertThat(huge.page()).isEqualTo(PageQuery.MAX_PAGE);
        assertThat(huge.size()).isEqualTo(PageQuery.MAX_SIZE);

        PageQuery negative = PageQuery.of(-3, -1, null, null, Map.of(), Set.of());
        assertThat(negative.page()).isZero();
        assertThat(negative.size()).isEqualTo(PageQuery.DEFAULT_SIZE);
    }

    @Test
    @DisplayName("maps an allow-listed sort field and rejects anything else")
    void resolvesSort() {
        PageQuery query = PageQuery.of(0, 25, "name", "asc", Map.of(), Set.of());
        assertThat(query.resolveSort(Map.of("name", "name"), "createdAt")).isEqualTo("name");
        assertThat(query.descending()).isFalse();

        PageQuery injected = PageQuery.of(0, 25, "name; DROP TABLE leads", "desc", Map.of(), Set.of());
        assertThatThrownBy(() -> injected.resolveSort(Map.of("name", "name"), "createdAt"))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).errorCode())
                .isEqualTo(ErrorCode.INVALID_SORT_FIELD);
    }

    @Test
    @DisplayName("only allow-listed filters are echoed back as applied")
    void filtersAreAllowListed() {
        PageQuery query = PageQuery.of(0, 25, null, null,
                Map.of("status", "NEW", "internal_flag", "1"), Set.of("status"));
        assertThat(query.appliedFilters()).containsExactly("status=NEW");
    }

    @Test
    @DisplayName("offset is derived from the clamped page")
    void computesOffset() {
        assertThat(PageQuery.of(3, 25, null, null, Map.of(), Set.of()).offset()).isEqualTo(75);
    }
}
