package com.platform.searchservice.common;

/**
 * 의존 서비스 불능 — 503으로 전파한다.
 *
 * 권한을 판정하지 못한 상태에서 빈 결과를 주면 "검색해도 안 나온다"로 조용히 오인된다.
 * 안 되는 건 안 된다고 보여야 한다(Wave B에서 확정된 fail-closed 정책과 동일).
 */
public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException(String message) {
        super(message);
    }

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
