package com.platform.common.error;

/** 의존 서비스(예: org-service gRPC) 불능 — 프론트가 장애를 인지하도록 503으로 전파한다. → HTTP 503. */
public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException(String message) { super(message); }
    public ServiceUnavailableException(String message, Throwable cause) { super(message, cause); }
}
