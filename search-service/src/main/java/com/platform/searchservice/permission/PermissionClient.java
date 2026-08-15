package com.platform.searchservice.permission;

/** org-service 권한 연동 창구 — 테스트는 페이크로 대체한다(wiki-backend와 같은 패턴). */
public interface PermissionClient {
    /**
     * 검색 결과 필터용 접근 범위.
     * org-service가 불능이면 빈 목록으로 삼키지 말고 예외를 던져야 한다 — 권한을 모르는 상태의
     * 빈 결과는 "검색해도 안 나오네"로 조용히 오인된다.
     */
    default SearchAccessScope accessibleResources(long userId) {
        AccessScope spaces = accessibleSpaces(userId);
        return spaces.all()
                ? SearchAccessScope.global()
                : SearchAccessScope.of(spaces.spaceIds(), java.util.Set.of());
    }

    /** Wave C 구현체·테스트의 소스 호환 경로. 신규 구현은 accessibleResources를 구현한다. */
    default AccessScope accessibleSpaces(long userId) {
        throw new UnsupportedOperationException("accessibleResources를 구현해야 합니다");
    }

    /**
     * 재색인 같은 운영 조작의 인가 — GLOBAL 리소스에 대한 ADMIN 권한(설계 §9).
     *
     * `GLOBAL grant를 하나라도 가졌나`가 아니라 `GLOBAL+ADMIN인가`를 묻는다: 전역 VIEWER는
     * 전 스페이스를 **볼** 수 있을 뿐이고, 색인을 통째로 다시 만들 권한과는 다르다.
     *
     * 판정 자체가 불가능하면(org-service 불능) false가 아니라 예외를 던진다 — 403과 503을
     * 섞으면 장애 중에 관리자가 "권한 없음"으로 오인된다.
     */
    boolean isGlobalAdmin(long userId);
}
