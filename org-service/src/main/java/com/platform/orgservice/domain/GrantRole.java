package com.platform.orgservice.domain;

public enum GrantRole {
    /** ADMIN ⊃ EDITOR ⊃ COMMENTER ⊃ VIEWER. COMMENTER(W23)는 보고 댓글만 달 수 있다. */
    VIEWER(1), COMMENTER(2), EDITOR(3), ADMIN(4);

    private final int rank;

    GrantRole(int rank) { this.rank = rank; }

    public int rank() { return rank; }

    /** ADMIN ⊃ EDITOR ⊃ COMMENTER ⊃ VIEWER 계층 — 이 role로 action이 허용되는가. */
    public boolean covers(PermAction action) { return rank >= action.requiredRank(); }
}
