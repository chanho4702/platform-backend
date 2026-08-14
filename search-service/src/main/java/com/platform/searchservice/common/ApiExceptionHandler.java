package com.platform.searchservice.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 관리자 REST(재색인)의 오류 계약. 본문 shape는 wiki-backend·org-service와 같은 {@code {"error": ...}}다.
 *
 * GraphQL 경로는 여기로 오지 않는다 — 그쪽은 {@code GraphQlExceptionResolver}가 extensions 계약으로
 * 따로 매핑한다(단일 URL이라 HTTP 상태로 구분할 수 없기 때문).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> notFound(NotFoundException e) { return Map.of("error", e.getMessage()); }

    @ExceptionHandler(ForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, String> forbidden(ForbiddenException e) { return Map.of("error", e.getMessage()); }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(ConflictException e) { return Map.of("error", e.getMessage()); }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException e) { return Map.of("error", e.getMessage()); }

    @ExceptionHandler(ServiceUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> unavailable(ServiceUnavailableException e) { return Map.of("error", e.getMessage()); }
}
