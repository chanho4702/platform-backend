package com.platform.searchservice.common;

/** 요청한 리소스가 없다 — 404. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
