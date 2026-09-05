package com.platform.orgservice.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 초대·상태 이력 한 줄. 권한 변경은 {@link GrantAudit}이 따로 남긴다 — 둘은 답해야 하는 질문이 다르다
 * ("이 사람이 어떻게 들어왔나" vs "누가 이 사람에게 권한을 줬나").
 *
 * <p>{@code memberId}는 초대를 만드는 시점에 없다(그 사람의 계정이 아직 없다). 그래서 memberId·invitationId
 * 둘 다 nullable이고, 최소 하나는 있어야 한다는 제약은 DB가 건다.
 */
@Entity
@Table(name = "member_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberEvent {

    private static final int MAX_DETAIL = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "invitation_id")
    private Long invitationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MemberEventType type;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(length = MAX_DETAIL)
    private String detail;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static MemberEvent forMember(Long memberId, MemberEventType type, Long actorId, String detail) {
        return build(memberId, null, type, actorId, detail);
    }

    public static MemberEvent forInvitation(Long invitationId, Long memberId, MemberEventType type,
                                            Long actorId, String detail) {
        return build(memberId, invitationId, type, actorId, detail);
    }

    private static MemberEvent build(Long memberId, Long invitationId, MemberEventType type,
                                     Long actorId, String detail) {
        MemberEvent e = new MemberEvent();
        e.memberId = memberId;
        e.invitationId = invitationId;
        e.type = type;
        e.actorId = actorId;
        e.detail = clamp(detail);
        return e;
    }

    /** 설명이 길다고 상태 변경을 실패시키지 않는다 — 자른 흔적을 남기고 계속한다. */
    private static String clamp(String value) {
        if (value == null) return null;
        return value.length() <= MAX_DETAIL ? value : value.substring(0, MAX_DETAIL - 1) + "…";
    }
}
