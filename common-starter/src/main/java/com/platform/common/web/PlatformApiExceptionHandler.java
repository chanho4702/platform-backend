package com.platform.common.web;

import com.platform.common.error.ConflictException;
import com.platform.common.error.ForbiddenException;
import com.platform.common.error.NotFoundException;
import com.platform.common.error.ServiceUnavailableException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 플랫폼 오류 계약 — 본문은 `{"error": "메시지"}`, 메시지는 한국어로 사용자에게 그대로 보인다.
 * (RFC 9457 ProblemDetail이 아니다 — 프론트 3곳이 이 모양을 파싱한다. 바꾸면 계약 변경이다.)
 *
 * 가장 낮은 우선순위다: 서비스가 자기 예외용 `@RestControllerAdvice`를 `@Order(0)`으로 두면 그쪽이 먼저 잡는다.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class PlatformApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> notFound(NotFoundException e) { return error(e.getMessage()); }

    @ExceptionHandler(ForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, String> forbidden(ForbiddenException e) { return error(e.getMessage()); }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(ConflictException e) { return error(e.getMessage()); }

    @ExceptionHandler(ServiceUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> unavailable(ServiceUnavailableException e) { return error(e.getMessage()); }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException e) { return error(e.getMessage()); }

    /** `@Valid` 실패 — 첫 필드 오류 메시지만. 전부 늘어놓으면 프론트가 첫 줄만 보여 주게 된다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(MethodArgumentNotValidException e) {
        return error(e.getBindingResult().getFieldErrors().stream()
                .findFirst().map(f -> f.getDefaultMessage()).orElse("요청 값이 올바르지 않습니다"));
    }

    /** 본문 파싱 실패(정의되지 않은 enum 값 등) — 원문은 클래스명이 섞여 사용자에게 보여줄 수 없다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> unreadable(HttpMessageNotReadableException e) { return error("요청 값이 올바르지 않습니다"); }

    private static Map<String, String> error(String message) {
        return Map.of("error", message == null ? "" : message);
    }
}
