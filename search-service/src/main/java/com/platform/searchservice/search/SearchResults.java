package com.platform.searchservice.search;

import java.util.List;

/** totalExact=false이면 total은 현재까지 확인한 가시 결과의 하한값이다. */
public record SearchResults(int total, boolean totalExact, int tookMs, List<SearchHit> hits) {
    public static SearchResults empty() {
        return new SearchResults(0, true, 0, List.of());
    }
}
