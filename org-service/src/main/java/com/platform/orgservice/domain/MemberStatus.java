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

    /**
     * auth-server 계정을 열어 둘 상태인가(P-02). 정지·퇴사만 닫는다 — 승인 대기(PENDING)는 로그인해서
     * "승인 대기" 화면을 보고, 재초대된 퇴사자가 초대를 소진하려면 토큰을 받을 수 있어야 한다.
     */
    public boolean allowsLogin() { return this != SUSPENDED && this != DEACTIVATED; }

    /** {@link #allowsLogin()}이 거짓인 상태들 — 동기화 질의용. */
    public static final java.util.Set<MemberStatus> LOGIN_BLOCKED = java.util.EnumSet.of(SUSPENDED, DEACTIVATED);
}
