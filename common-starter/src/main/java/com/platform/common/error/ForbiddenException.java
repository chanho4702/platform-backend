package com.platform.common.error;

/** 권한이 없다 — 인가 거부. 가용성 장애와 섞지 않는다. → HTTP 403. */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) { super(message); }
    public ForbiddenException(String message, Throwable cause) { super(message, cause); }
}
