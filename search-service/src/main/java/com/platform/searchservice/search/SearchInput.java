package com.platform.searchservice.search;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** schema.graphqls의 SearchInput과 같은 입력 모델. */
public record SearchInput(
        String query,
        List<String> spaceIds,
        List<DocType> docTypes,
        Boolean includeDrafts,
        Integer page,
        Integer size
) {
    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 100;

    public Set<Long> requestedSpaceIds() {
        if (spaceIds == null || spaceIds.isEmpty()) return Set.of();
        Set<Long> parsed = new LinkedHashSet<>();
        for (String raw : spaceIds) {
            try {
                parsed.add(Long.parseLong(raw));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("spaceIds는 숫자 ID여야 합니다: " + raw, e);
            }
        }
        return Set.copyOf(parsed);
    }

    public boolean draftsIncluded() {
        return Boolean.TRUE.equals(includeDrafts);
    }

    public int normalizedPage() {
        return Math.max(page == null ? 0 : page, 0);
    }

    public int normalizedSize() {
        int requested = size == null ? DEFAULT_SIZE : size;
        return Math.max(0, Math.min(requested, MAX_SIZE));
    }
}
