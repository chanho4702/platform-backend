package com.platform.orgservice.domain;

/** member_event.type — 초대·상태 이력. 권한 변경은 grant_audit이 따로 남긴다. */
public enum MemberEventType {
    INVITED, INVITE_RESENT, INVITE_REVOKED, INVITE_EXPIRED,
    JOINED, APPROVED, SUSPENDED, REACTIVATED, DEACTIVATED,
    TEAM_ADDED, TEAM_REMOVED,
    /** 관리자가 남의 아바타를 올리거나 지웠다 — 본인이 바꾼 것은 남기지 않는다. */
    AVATAR_CHANGED, AVATAR_REMOVED,
    /** Keycloak 계정 비활성/활성 호출이 실패했다 — 우리 쪽 상태는 이미 바뀌었으니 흔적만 남긴다. */
    KEYCLOAK_DISABLED_FAILED
}
