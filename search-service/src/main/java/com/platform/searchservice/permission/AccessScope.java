package com.platform.searchservice.permission;

import java.util.Set;

/**
 * 사용자가 검색 결과로 볼 수 있는 스페이스 범위. all=true면 전 스페이스(GLOBAL grant 보유자).
 * wiki-backend의 같은 이름 record와 의미가 같다 — 두 서비스가 같은 org 계약을 소비한다.
 */
public record AccessScope(boolean all, Set<Long> spaceIds) {

    public static AccessScope global() {
        return new AccessScope(true, Set.of());
    }

    public static AccessScope of(Set<Long> spaceIds) {
        return new AccessScope(false, spaceIds);
    }

    /** 볼 수 있는 게 하나도 없다 — 질의를 보내지 않고 빈 결과로 끝낼 수 있다. */
    public boolean isEmpty() {
        return !all && spaceIds.isEmpty();
    }

    /**
     * 클라이언트가 요청한 스페이스와의 교집합. 요청이 비어 있으면 접근 가능 전체다.
     * **좁히기만 가능하고 넓힐 수는 없다** — 이 방향이 뒤집히면 권한 우회가 된다.
     */
    public Set<Long> resolveFilter(Set<Long> requested) {
        if (requested == null || requested.isEmpty()) return spaceIds;
        if (all) return requested;
        return requested.stream().filter(spaceIds::contains).collect(java.util.stream.Collectors.toSet());
    }
}
