package com.platform.searchservice.search;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** schema.graphqls의 SearchInput과 같은 입력 모델. */
public record SearchInput(
        String query,
        List<String> spaceIds,
        List<String> projectIds,
        List<DocType> docTypes,
        Boolean includeDrafts,
        Integer page,
        Integer size
) {
    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 100;

    /** Wave C 호출부와 소스 호환 — 프로젝트 필터가 없던 기존 Java 테스트/클라이언트용. */
    public SearchInput(
            String query,
            List<String> spaceIds,
            List<DocType> docTypes,
            Boolean includeDrafts,
            Integer page,
            Integer size) {
        this(query, spaceIds, List.of(), docTypes, includeDrafts, page, size);
    }

    public Set<Long> requestedSpaceIds() {
        return parseIds(spaceIds, "spaceIds");
    }

    public Set<Long> requestedProjectIds() {
        return parseIds(projectIds, "projectIds");
    }

    private static Set<Long> parseIds(List<String> ids, String field) {
        if (ids == null || ids.isEmpty()) return Set.of();
        Set<Long> parsed = new LinkedHashSet<>();
        for (String raw : ids) {
            try {
                parsed.add(Long.parseLong(raw));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(field + "는 숫자 ID여야 합니다: " + raw, e);
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
