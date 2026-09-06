package com.platform.orgservice.mail.dto;

/**
 * 큐잉 결과. {@code disabled}면 아무것도 넣지 않았다는 뜻이고 {@code accepted}는 0이다 —
 * 소비자는 이것을 오류가 아니라 "메일이 꺼져 있음"으로 다뤄야 한다(화면이 다른 안내를 낸다).
 */
public record InternalMailResponse(int accepted, boolean disabled) {
}
