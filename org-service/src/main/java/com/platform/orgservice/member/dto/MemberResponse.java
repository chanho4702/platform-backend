package com.platform.orgservice.member.dto;

import com.platform.orgservice.domain.Member;

public record MemberResponse(Long id, String displayName, String email, String status) {
    public static MemberResponse from(Member m) {
        return new MemberResponse(m.getId(), m.getDisplayName(), m.getEmail(), m.getStatus().name());
    }
}
