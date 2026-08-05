package com.platform.searchservice.search;

import java.util.List;

/** schema.graphqls의 SearchResults 응답 모델. */
public record SearchResults(int total, int tookMs, List<SearchHit> hits) {
    public static SearchResults empty() {
        return new SearchResults(0, 0, List.of());
    }
}
