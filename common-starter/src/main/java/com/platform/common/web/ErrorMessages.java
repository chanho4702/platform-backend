package com.platform.common.web;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * 오류 문구 규칙 — 플랫폼 오류 본문의 {@code error}는 프론트가 그대로 화면에 띄운다.
 *
 * <p>서비스가 사용자에게 보이려고 쓴 문구는 한국어다(플랫폼 규약). 그래서 "한글이 하나라도 있으면 의도된 문구"로
 * 본다. 라이브러리·프레임워크가 만든 원문("No enum constant com.platform…", "For input string", "Request method
 * 'PATCH' is not supported")은 클래스명·내부 구조가 섞여 있어 고정 문구로 바꾼다.
 */
public final class ErrorMessages {

    public static final String BAD_REQUEST = "요청 값이 올바르지 않습니다";
    public static final String TYPE_MISMATCH = "요청 값의 형식이 올바르지 않습니다";
    public static final String MISSING_PARAMETER = "필수 요청 값이 빠졌습니다";
    public static final String OPTIMISTIC_LOCK = "다른 사용자가 먼저 수정했습니다. 새로고침 후 다시 시도해 주세요";
    public static final String UNAUTHORIZED = "로그인이 필요합니다";
    public static final String FORBIDDEN = "권한이 없습니다";
    public static final String INTERNAL = "일시적인 오류가 발생했습니다";

    private static final Map<Integer, String> BY_STATUS = Map.ofEntries(
            Map.entry(400, BAD_REQUEST),
            Map.entry(401, UNAUTHORIZED),
            Map.entry(403, FORBIDDEN),
            Map.entry(404, "요청한 대상을 찾을 수 없습니다"),
            Map.entry(405, "지원하지 않는 요청 방식입니다"),
            Map.entry(406, "지원하지 않는 응답 형식입니다"),
            Map.entry(409, "요청이 현재 상태와 충돌합니다"),
            Map.entry(413, "요청 크기가 너무 큽니다"),
            Map.entry(415, "지원하지 않는 요청 형식입니다"),
            Map.entry(429, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요"),
            Map.entry(503, "서비스를 일시적으로 사용할 수 없습니다"));

    private ErrorMessages() {}

    /** 사용자에게 그대로 보여도 되는 문구인가 — 서비스가 쓴 한국어 문구만 통과시킨다. */
    public static boolean isUserFacing(String message) {
        if (message == null || message.isBlank()) return false;
        for (int i = 0; i < message.length(); i++) {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(message.charAt(i));
            if (block == Character.UnicodeBlock.HANGUL_SYLLABLES
                    || block == Character.UnicodeBlock.HANGUL_JAMO
                    || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO) {
                return true;
            }
        }
        return false;
    }

    /** 의도된 문구면 그대로, 아니면 {@code fallback}. */
    public static String userFacingOr(String message, String fallback) {
        return isUserFacing(message) ? message : fallback;
    }

    /** 상태 코드별 기본 문구. 5xx는 원인을 숨긴다. */
    public static String forStatus(int status) {
        String known = BY_STATUS.get(status);
        if (known != null) return known;
        if (status >= 500) return INTERNAL;
        return HttpStatus.resolve(status) == null ? BAD_REQUEST : "요청을 처리할 수 없습니다";
    }
}
