package com.platform.orgservice.creation.dto;

import com.platform.orgservice.domain.CreationMode;

import java.util.List;

/** 생략한 쪽(space·project가 null)은 그대로 둔다. mode가 TEAMS가 아니면 teamIds는 무시하고 비운다. */
public record CreationPolicyUpdateRequest(Entry space, Entry project) {

    public record Entry(CreationMode mode, List<Long> teamIds) {}
}
