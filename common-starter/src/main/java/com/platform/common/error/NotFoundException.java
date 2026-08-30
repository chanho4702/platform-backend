package com.platform.common.error;

/** 대상이 없다. 메시지는 사용자에게 그대로 보인다(한국어). → HTTP 404. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) { super(message); }
    public NotFoundException(String message, Throwable cause) { super(message, cause); }
}
