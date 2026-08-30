package com.platform.common.error;

/** 상태 충돌(낙관적 락 실패·규칙 위반). 재시도는 사용자 판단이다. → HTTP 409. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) { super(message); }
    public ConflictException(String message, Throwable cause) { super(message, cause); }
}
