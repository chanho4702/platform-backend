package com.platform.orgservice.domain;

public enum PermAction {
    VIEW(1), EDIT(2), ADMIN(3);

    private final int requiredRank;

    PermAction(int requiredRank) { this.requiredRank = requiredRank; }

    public int requiredRank() { return requiredRank; }
}
