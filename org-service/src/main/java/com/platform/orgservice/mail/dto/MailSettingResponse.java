package com.platform.orgservice.mail.dto;

import com.platform.orgservice.domain.MailSetting;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 메일 설정 한 벌. <b>비밀번호는 어떤 경우에도 담기지 않는다</b> — 저장돼 있는지({@code passwordSet})만
 * 알려 주고, 화면은 "저장됨"으로 표시한 뒤 바꿀 때만 새 값을 받는다.
 */
@Schema(description = "플랫폼 메일 설정. 비밀번호는 응답에 담기지 않고 저장 여부만 알려 준다.")
public record MailSettingResponse(
        @Schema(description = "메일 발송 사용 여부. false면 큐에 넣지 않고 곧바로 disabled로 답한다.", example = "true")
        boolean enabled,
        @Schema(description = "설치 시 고른 인프라 모드(MAIL_MODE) — none | external | relay | full | dev. 읽기 전용 안내값이다.",
                example = "relay", allowableValues = {"none", "external", "relay", "full", "dev"})
        String mode,
        @Schema(description = "SMTP 호스트", example = "mail-relay") String host,
        @Schema(description = "SMTP 포트", example = "25") int port,
        @Schema(description = "SMTP 사용자. 비면 인증 없이 보낸다(사내 릴레이).", example = "platform@example.com")
        String username,
        @Schema(description = "비밀번호가 저장돼 있는가. 값 자체는 절대 내려가지 않는다.", example = "true")
        boolean passwordSet,
        @Schema(description = "전송 보안", example = "STARTTLS", allowableValues = {"NONE", "STARTTLS", "SSL"})
        String tls,
        @Schema(description = "보내는 주소", example = "no-reply@example.com") String fromAddress,
        @Schema(description = "보내는 이름", example = "플랫폼") String fromName,
        @Schema(description = "마지막 저장 시각") Instant updatedAt,
        @Schema(description = "마지막으로 저장한 멤버 id. 최초 시드(env)면 null.", example = "1") Long updatedBy) {

    public static MailSettingResponse of(MailSetting s, String mode) {
        return new MailSettingResponse(
                s.isEnabled(), mode, s.getHost(), s.getPort(), s.getUsername(), s.hasPassword(),
                s.getTls().name(), s.getFromAddress(), s.getFromName(), s.getUpdatedAt(), s.getUpdatedBy());
    }
}
