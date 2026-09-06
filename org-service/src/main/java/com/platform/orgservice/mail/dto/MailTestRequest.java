package com.platform.orgservice.mail.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** 테스트 발송 대상. 비우면 요청한 관리자 본인의 JWT 이메일로 보낸다. */
@Schema(description = "테스트 발송 대상. 비우면 요청한 관리자 본인에게 보낸다.")
public record MailTestRequest(
        @Schema(description = "받는 주소. 생략하면 요청자의 이메일.", example = "chanho@example.com")
        @Size(max = 320, message = "받는 주소는 320자 이하여야 합니다") String to) {
}
