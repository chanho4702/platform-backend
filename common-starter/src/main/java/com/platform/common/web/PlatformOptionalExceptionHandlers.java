package com.platform.common.web;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 의존이 서비스마다 다른 예외의 계약 — 각 어드바이스는 해당 라이브러리가 있을 때만 등록된다
 * ({@link PlatformWebAutoConfiguration}의 조건부 import). 한 클래스에 모으면 그 라이브러리가 없는 서비스에서
 * 핸들러 클래스 로딩이 실패한다.
 */
public final class PlatformOptionalExceptionHandlers {

    private PlatformOptionalExceptionHandlers() {}

    /** JPA `@Version` 충돌(Hibernate → Spring 변환) — 플랫폼의 낙관적 락 계약은 409 + 한국어 문구다. */
    @RestControllerAdvice
    @Order(Ordered.LOWEST_PRECEDENCE)
    public static class OptimisticLock {
        @ExceptionHandler(OptimisticLockingFailureException.class)
        @ResponseStatus(HttpStatus.CONFLICT)
        public Map<String, String> locked(OptimisticLockingFailureException e) {
            return PlatformApiExceptionHandler.error(ErrorMessages.OPTIMISTIC_LOCK);
        }
    }

    /** `@PreAuthorize` 등 메서드 보안 거부 — 원문("Access Denied")은 영어라 고정 문구로. */
    @RestControllerAdvice
    @Order(Ordered.LOWEST_PRECEDENCE)
    public static class AccessDenied {
        @ExceptionHandler(AccessDeniedException.class)
        @ResponseStatus(HttpStatus.FORBIDDEN)
        public Map<String, String> denied(AccessDeniedException e) {
            return PlatformApiExceptionHandler.error(ErrorMessages.userFacingOr(e.getMessage(), ErrorMessages.FORBIDDEN));
        }
    }

    /** 클래스 레벨 `@Validated` 메서드 검증 — 첫 위반 메시지만. */
    @RestControllerAdvice
    @Order(Ordered.LOWEST_PRECEDENCE)
    public static class ConstraintViolations {
        @ExceptionHandler(ConstraintViolationException.class)
        @ResponseStatus(HttpStatus.BAD_REQUEST)
        public Map<String, String> violated(ConstraintViolationException e) {
            String message = e.getConstraintViolations() == null ? null
                    : e.getConstraintViolations().stream().map(ConstraintViolation::getMessage)
                        .filter(m -> m != null && !m.isBlank()).findFirst().orElse(null);
            return PlatformApiExceptionHandler.error(message == null ? ErrorMessages.BAD_REQUEST : message);
        }
    }
}
