package com.platform.common.error;

/**
 * 요청이 규칙에 맞지 않는다 — 서비스가 사용자에게 보여 줄 문구를 직접 고른 400. → HTTP 400.
 *
 * <p>{@link IllegalArgumentException}은 라이브러리도 던지므로(enum 변환·숫자 파싱 등) 공용 핸들러가 그 원문을
 * 한국어가 아니면 사용자에게 내보내지 않는다. 사용자에게 보일 문구를 의도적으로 던질 때는 이 예외를 쓴다.
 */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) { super(message); }
    public BadRequestException(String message, Throwable cause) { super(message, cause); }
}
