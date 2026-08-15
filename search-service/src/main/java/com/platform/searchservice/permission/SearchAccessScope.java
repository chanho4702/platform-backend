package com.platform.searchservice.permission;

import java.util.Set;
import java.util.stream.Collectors;

/** 한 번의 grant 조회로 계산한 통합 검색 권한 범위. GLOBAL이면 두 도메인 모두 전체다. */
public record SearchAccessScope(boolean all, Set<Long> spaceIds, Set<Long> projectIds) {

    public SearchAccessScope {
        spaceIds = Set.copyOf(spaceIds);
        projectIds = Set.copyOf(projectIds);
    }

    public static SearchAccessScope global() {
        return new SearchAccessScope(true, Set.of(), Set.of());
    }

    public static SearchAccessScope of(Set<Long> spaceIds, Set<Long> projectIds) {
        return new SearchAccessScope(false, spaceIds, projectIds);
    }

    public Set<Long> resolveSpaces(Set<Long> requested) {
        return resolve(spaceIds, requested);
    }

    public Set<Long> resolveProjects(Set<Long> requested) {
        return resolve(projectIds, requested);
    }

    public boolean canSearchSpaces(Set<Long> requested) {
        return all || !resolveSpaces(requested).isEmpty();
    }

    public boolean canSearchProjects(Set<Long> requested) {
        return all || !resolveProjects(requested).isEmpty();
    }

    private Set<Long> resolve(Set<Long> granted, Set<Long> requested) {
        if (requested == null || requested.isEmpty()) return granted;
        if (all) return requested;
        return requested.stream().filter(granted::contains).collect(Collectors.toUnmodifiableSet());
    }
}
