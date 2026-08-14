package com.platform.searchservice.common;

/**
 * 인증은 됐지만 권한이 없다 — 403.
 *
 * 권한을 **판정하지 못한** 경우와 반드시 구분한다: 그쪽은 {@link ServiceUnavailableException}(503)이다.
 * 둘을 같게 취급하면 org-service 장애 중에 관리자가 "권한 없음"으로 오인된다.
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
