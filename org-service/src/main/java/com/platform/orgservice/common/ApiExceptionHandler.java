package com.platform.orgservice.common;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** org만의 것 — 나머지(404·400 등)는 common-starter의 PlatformApiExceptionHandler가 같은 계약으로 맡는다. */
@RestControllerAdvice
@Order(0)
public class ApiExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, String> forbidden(AccessDeniedException e) {
        return Map.of("error", e.getMessage());
    }
}
