package com.platform.orgservice.member.dto;

import com.platform.orgservice.domain.MemberEvent;

import java.time.Instant;

/** 초대·상태 이력 한 줄. 권한 이력({@code /grants/audit})과 화면에서 나란히 놓인다. */
public record MemberEventResponse(Long id, Long memberId, Long invitationId, String type,
                                  Long actorId, String detail, Instant createdAt) {

    public static MemberEventResponse from(MemberEvent e) {
        return new MemberEventResponse(e.getId(), e.getMemberId(), e.getInvitationId(),
                e.getType().name(), e.getActorId(), e.getDetail(), e.getCreatedAt());
    }
}
