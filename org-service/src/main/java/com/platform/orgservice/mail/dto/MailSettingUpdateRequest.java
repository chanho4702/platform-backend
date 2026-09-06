package com.platform.orgservice.mail.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 메일 설정 저장.
 *
 * <p>{@code password}는 세 갈래다 — <b>생략</b>(필드 없음 = 지금 값 유지), <b>빈 문자열</b>(삭제),
 * <b>값</b>(교체). 화면이 "저장됨"만 보고 매번 다시 입력하게 만들지 않으려면 이 구분이 필요하다.
 */
@Schema(description = "메일 설정 저장. password는 생략하면 유지, 빈 문자열이면 삭제, 값이 있으면 교체다.")
public record MailSettingUpdateRequest(
        @Schema(description = "메일 발송 사용 여부. true면 host·port·fromAddress가 필수다.", example = "true")
        boolean enabled,
        @Schema(description = "SMTP 호스트", example = "mail-relay")
        @Size(max = 255, message = "호스트는 255자 이하여야 합니다") String host,
        @Schema(description = "SMTP 포트", example = "25")
        @Min(value = 1, message = "포트는 1~65535 사이여야 합니다")
        @Max(value = 65535, message = "포트는 1~65535 사이여야 합니다") int port,
        @Schema(description = "SMTP 사용자. 비우면 인증 없이 보낸다.", example = "platform@example.com")
        @Size(max = 320, message = "사용자는 320자 이하여야 합니다") String username,
        @Schema(description = "SMTP 비밀번호. 생략=유지, \"\"=삭제, 값=교체. 저장하려면 ORG_SETTINGS_ENC_KEY가 있어야 한다.")
        @Size(max = 500, message = "비밀번호는 500자 이하여야 합니다") String password,
        @Schema(description = "전송 보안", example = "STARTTLS", allowableValues = {"NONE", "STARTTLS", "SSL"})
        String tls,
        @Schema(description = "보내는 주소", example = "no-reply@example.com")
        @Size(max = 320, message = "보내는 주소는 320자 이하여야 합니다") String fromAddress,
        @Schema(description = "보내는 이름", example = "플랫폼")
        @Size(max = 120, message = "보내는 이름은 120자 이하여야 합니다") String fromName) {
}
