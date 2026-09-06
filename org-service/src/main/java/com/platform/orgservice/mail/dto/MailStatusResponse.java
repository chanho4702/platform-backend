package com.platform.orgservice.mail.dto;

/**
 * 소비자가 "메일 켜짐" UI를 그릴 근거. 저장된 {@code enabled}가 아니라 <b>실제로 보낼 수 있는가</b>다 —
 * 켜 두었지만 호스트·발신자가 비어 있으면 켜졌다고 말하지 않는다.
 */
public record MailStatusResponse(boolean enabled) {
}
