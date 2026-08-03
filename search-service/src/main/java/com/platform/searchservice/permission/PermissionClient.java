package com.platform.searchservice.permission;

/** org-service 권한 연동 창구 — 테스트는 페이크로 대체한다(wiki-backend와 같은 패턴). */
public interface PermissionClient {
    /**
     * 검색 결과 필터용 접근 범위.
     * org-service가 불능이면 빈 목록으로 삼키지 말고 예외를 던져야 한다 — 권한을 모르는 상태의
     * 빈 결과는 "검색해도 안 나오네"로 조용히 오인된다.
     */
    AccessScope accessibleSpaces(long userId);
}
