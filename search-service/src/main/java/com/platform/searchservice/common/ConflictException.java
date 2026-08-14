package com.platform.searchservice.common;

/** 현재 상태와 충돌하는 요청 — 409. 재색인이 이미 실행 중인 경우가 이 서비스의 유일한 사례다. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
