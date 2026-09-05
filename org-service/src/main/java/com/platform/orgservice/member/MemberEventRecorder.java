package com.platform.orgservice.member;

import com.platform.orgservice.domain.MemberEvent;
import com.platform.orgservice.domain.MemberEventType;
import com.platform.orgservice.repository.MemberEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 초대·상태 이력을 남기는 단일 창구. 기록이 실패했다고 상태 변경을 되돌리지는 않는다. */
@Component
@RequiredArgsConstructor
public class MemberEventRecorder {

    private final MemberEventRepository events;

    public void member(Long memberId, MemberEventType type, Long actorId, String detail) {
        events.save(MemberEvent.forMember(memberId, type, actorId, detail));
    }

    public void invitation(Long invitationId, Long memberId, MemberEventType type, Long actorId, String detail) {
        events.save(MemberEvent.forInvitation(invitationId, memberId, type, actorId, detail));
    }
}
