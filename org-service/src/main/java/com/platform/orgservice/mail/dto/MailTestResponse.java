package com.platform.orgservice.mail.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 테스트 발송 결과. 실패해도 200이고 {@code ok:false}다 — 화면이 SMTP가 알려 준 문구를 그대로
 * 보여 줘야 관리자가 호스트·인증·TLS 중 무엇이 틀렸는지 안다.
 */
@Schema(description = "테스트 발송 결과. 실패해도 200이고 error에 SMTP 문구가 그대로 담긴다.")
public record MailTestResponse(
        @Schema(description = "보냈는가", example = "false") boolean ok,
        @Schema(description = "실패 사유(SMTP 원문). 성공이면 null.",
                example = "Couldn't connect to host, port: mail-relay, 25") String error) {

    public static MailTestResponse sent() { return new MailTestResponse(true, null); }

    public static MailTestResponse failed(String error) { return new MailTestResponse(false, error); }
}
