package com.platform.common.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 서블릿 오류 디스패치(/error)의 플랫폼 판 — Boot 기본(BasicErrorController)의
 * `{"timestamp","status","error":"Internal Server Error","path"}` 대신 `{"error": 한국어 메시지}`를 낸다(P-11).
 *
 * <p>여기 오는 것: 컨트롤러 어드바이스가 잡지 않은 예외(500), 프레임워크가 상태만 정한 오류(404 정적 리소스 없음·
 * 405·406·413·415), `@ResponseStatus`가 붙은 서비스 예외, `sendError`를 부른 필터. 어드바이스 안의 catch-all로
 * 처리하지 않는 이유: `@ExceptionHandler(Exception.class)`는 같은 우선순위의 서비스 어드바이스를 앞지를 수 있다.
 *
 * <p>5xx는 원인을 본문에 싣지 않고 로그에만 남긴다. 4xx 사유는 서비스가 쓴 한국어면 그대로, 아니면 상태별 문구다.
 * 서비스가 자기 {@code ErrorController} 빈을 두면 이 컨트롤러는 물러난다.
 */
@RestController
public class PlatformErrorController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(PlatformErrorController.class);

    @RequestMapping(value = "${spring.web.error.path:${error.path:/error}}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> error(HttpServletRequest request) {
        int status = statusOf(request);
        Object reason = request.getAttribute(RequestDispatcher.ERROR_MESSAGE);
        String message;
        if (status >= 500) {
            Object cause = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
            Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
            if (cause instanceof Throwable t) {
                log.error("처리되지 않은 오류 {} {}", status, uri, t);
            } else {
                log.error("서버 오류 응답 {} {}", status, uri);
            }
            message = ErrorMessages.forStatus(status);
        } else {
            message = ErrorMessages.userFacingOr(reason == null ? null : reason.toString(), ErrorMessages.forStatus(status));
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(PlatformApiExceptionHandler.error(message));
    }

    private static int statusOf(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (code instanceof Integer i && i >= 400 && i <= 599) return i;
        return 500;
    }
}
