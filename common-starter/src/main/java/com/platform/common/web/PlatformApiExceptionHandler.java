package com.platform.common.web;

import com.platform.common.error.BadRequestException;
import com.platform.common.error.ConflictException;
import com.platform.common.error.ForbiddenException;
import com.platform.common.error.NotFoundException;
import com.platform.common.error.ServiceUnavailableException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/**
 * 플랫폼 오류 계약 — 본문은 `{"error": "메시지"}`, 메시지는 한국어로 사용자에게 그대로 보인다.
 * (RFC 9457 ProblemDetail이 아니다 — 프론트 3곳이 이 모양을 파싱한다. 바꾸면 계약 변경이다.)
 *
 * 가장 낮은 우선순위다: 서비스가 자기 예외용 `@RestControllerAdvice`를 `@Order(0)`으로 두면 그쪽이 먼저 잡는다.
 *
 * 여기서 잡지 않은 것(미처리 예외·405·415 등)은 서블릿 오류 디스패치로 {@link PlatformErrorController}가 같은 모양으로
 * 낸다. 의존이 선택적인 예외(낙관적 락·AccessDenied·ConstraintViolation)는 {@link PlatformOptionalExceptionHandlers}에
 * 있다 — 이 클래스가 그 타입을 참조하면 해당 라이브러리가 없는 서비스에서 어드바이스 전체가 로드되지 않는다.
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

    /** 서비스가 사용자에게 보일 문구를 직접 고른 400 — 그대로 싣는다. */
    @ExceptionHandler(BadRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(BadRequestException e) { return error(e.getMessage()); }

    /**
     * IAE는 라이브러리도 던진다(`No enum constant com.platform…`, NumberFormatException의 `For input string`).
     * 서비스가 쓴 한국어 문구만 그대로 두고, 나머지는 고정 문구로 바꾼다 — 기존 서비스들이 한국어 IAE를
     * 사용자 문구로 써 왔으므로(하위 호환) 전부 막지는 않는다. 새 코드는 {@link BadRequestException}을 쓴다.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> illegalArgument(IllegalArgumentException e) {
        return error(ErrorMessages.userFacingOr(e.getMessage(), ErrorMessages.BAD_REQUEST));
    }

    /** `@Valid` 실패 — 첫 필드 오류 메시지만. 전부 늘어놓으면 프론트가 첫 줄만 보여 주게 된다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(MethodArgumentNotValidException e) {
        return error(e.getBindingResult().getFieldErrors().stream()
                .findFirst().map(f -> f.getDefaultMessage()).orElse(ErrorMessages.BAD_REQUEST));
    }

    /** 메서드 파라미터 검증(@RequestParam @Min 등, Spring 6.1+) — 첫 오류 메시지만. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> methodValidation(HandlerMethodValidationException e) {
        return error(e.getAllErrors().stream()
                .map(MessageSourceResolvable::getDefaultMessage)
                .filter(m -> m != null && !m.isBlank())
                .findFirst().orElse(ErrorMessages.BAD_REQUEST));
    }

    /** 경로·쿼리 값의 타입 불일치(`/pages/abc`) — 원문은 변환기 클래스명이 섞인다. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> typeMismatch(MethodArgumentTypeMismatchException e) {
        return error(ErrorMessages.TYPE_MISMATCH);
    }

    /** 필수 쿼리 파라미터·헤더·쿠키 누락. */
    @ExceptionHandler(ServletRequestBindingException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> missing(ServletRequestBindingException e) {
        return error(ErrorMessages.MISSING_PARAMETER);
    }

    /** 본문 파싱 실패(정의되지 않은 enum 값 등) — 원문은 클래스명이 섞여 사용자에게 보여줄 수 없다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> unreadable(HttpMessageNotReadableException e) { return error(ErrorMessages.BAD_REQUEST); }

    static Map<String, String> error(String message) {
        return Map.of("error", message == null ? "" : message);
    }
}
