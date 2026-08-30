package com.platform.orgservice.domain;

public enum PermAction {
    /** 계층: VIEW < COMMENT < EDIT < ADMIN. COMMENT(W23)는 컨플루언스 "댓글 추가"에 해당한다. */
    VIEW(1), COMMENT(2), EDIT(3), ADMIN(4);

    private final int requiredRank;

    PermAction(int requiredRank) { this.requiredRank = requiredRank; }

    public int requiredRank() { return requiredRank; }
}
