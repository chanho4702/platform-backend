package com.platform.orgservice.domain;

/**
 * 사용자 수명주기(U1).
 *
 * <p>PENDING은 "초대 없이 들어온 계정"이다 — 로그인은 됐지만 관리자가 승인하기 전까지 아무것도 못 한다.
 * SUSPENDED는 되돌릴 수 있는 일시 정지, DEACTIVATED는 퇴사(되돌리려면 재초대)다.
 * ACTIVE가 아니면 gRPC 권한 판정이 무조건 거부한다(fail-closed).
 */
public enum MemberStatus {
    PENDING, ACTIVE, SUSPENDED, DEACTIVATED;

    public boolean isActive() { return this == ACTIVE; }
}
