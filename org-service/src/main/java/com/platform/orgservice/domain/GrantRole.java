package com.platform.orgservice.domain;

public enum GrantRole {
    VIEWER(1), EDITOR(2), ADMIN(3);

    private final int rank;

    GrantRole(int rank) { this.rank = rank; }

    public int rank() { return rank; }

    /** ADMIN ⊃ EDITOR ⊃ VIEWER 계층 — 이 role로 action이 허용되는가. */
    public boolean covers(PermAction action) { return rank >= action.requiredRank(); }
}
