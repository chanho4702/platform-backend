package com.platform.orgservice.domain;

import com.platform.common.error.ConflictException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {

    @Id
    private Long id; // auth-server user id(JWT sub) — 자체 시퀀스 없음

    @Column(nullable = false)
    private String displayName;

    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberStatus status;

    /** HUMAN(기본) | AGENT — 로그인 없는 AI 에이전트 페르소나 구분(스펙 D6). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberKind kind;

    /** 어떻게 활성 사용자가 됐는가(U1). 초대 제도 이전 계정은 LEGACY. */
    @Enumerated(EnumType.STRING)
    @Column(name = "joined_via", nullable = false, length = 16)
    private JoinedVia joinedVia;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    /** 이미 쓰고 있던 계정(LEGACY)·테스트 픽스처. 새 로그인은 {@link #joining}을 쓴다. */
    public static Member of(Long id, String displayName, String email) {
        Member m = base(id, displayName != null ? displayName : "user-" + id, email);
        m.status = MemberStatus.ACTIVE;
        m.joinedVia = JoinedVia.LEGACY;
        return m;
    }

    /**
     * 처음 로그인한 사람 — 초대가 소진되기 전까지는 PENDING이다.
     * 초대 없이 들어온 계정이 곧바로 아무 데나 들어가지 못하게 하는 것이 U1의 핵심이다.
     */
    public static Member joining(Long id, String displayName, String email) {
        Member m = base(id, displayName != null ? displayName : "user-" + id, email);
        m.status = MemberStatus.PENDING;
        m.joinedVia = JoinedVia.LEGACY; // 활성화되는 순간 INVITE/APPROVAL로 덮인다
        return m;
    }

    /** agent-service가 등록하는 에이전트 페르소나 — 로그인이 없으므로 승인 대기 개념이 없다. */
    public static Member agentOf(Long id, String displayName, String email) {
        Member m = base(id, displayName != null ? displayName : "agent-" + id, email);
        m.status = MemberStatus.ACTIVE;
        m.kind = MemberKind.AGENT;
        m.joinedVia = JoinedVia.LEGACY;
        return m;
    }

    private static Member base(Long id, String displayName, String email) {
        Member m = new Member();
        m.id = id;
        m.displayName = displayName;
        m.email = email;
        m.kind = MemberKind.HUMAN;
        return m;
    }

    /** JIT 미러링 — 클레임이 바뀐 경우만 갱신. */
    public void refresh(String displayName, String email) {
        if (displayName != null) this.displayName = displayName;
        if (email != null) this.email = email;
    }

    /** 에이전트 등록 재호출 — 이름/이메일 갱신 + kind=AGENT 유지(사람 mirror가 덮어써도 되돌린다는 뜻은 아님). */
    public void refreshAsAgent(String displayName, String email) {
        if (displayName != null) this.displayName = displayName;
        if (email != null) this.email = email;
        this.kind = MemberKind.AGENT;
    }

    /** 초대 소진 — PENDING이 아니어도 멱등하게 활성으로 둔다(이미 활성인 사람에게 초대가 도착할 수 있다). */
    public void acceptInvitation() {
        this.status = MemberStatus.ACTIVE;
        this.joinedVia = JoinedVia.INVITE;
        this.suspendedAt = null;
        this.deactivatedAt = null;
    }

    public void approve(long actorId, Instant at) {
        requireStatus(MemberStatus.PENDING, "승인 대기 중인 계정이 아닙니다");
        this.status = MemberStatus.ACTIVE;
        this.joinedVia = JoinedVia.APPROVAL;
        this.approvedBy = actorId;
        this.approvedAt = at;
    }

    public void suspend(Instant at) {
        requireStatus(MemberStatus.ACTIVE, "활성 상태에서만 정지할 수 있습니다");
        this.status = MemberStatus.SUSPENDED;
        this.suspendedAt = at;
    }

    public void reactivate() {
        requireStatus(MemberStatus.SUSPENDED, "정지된 계정만 다시 활성화할 수 있습니다");
        this.status = MemberStatus.ACTIVE;
        this.suspendedAt = null;
    }

    public void deactivate(Instant at) {
        if (status == MemberStatus.DEACTIVATED) return; // 멱등
        this.status = MemberStatus.DEACTIVATED;
        this.deactivatedAt = at;
    }

    /** 재초대 대상으로 되돌린다 — 퇴사자를 다시 부르는 유일한 경로(스펙 §3.2 상태 전이). */
    public void reopenForInvitation() {
        this.status = MemberStatus.PENDING;
        this.deactivatedAt = null;
        this.suspendedAt = null;
    }

    /** 부트스트랩 관리자 시드가 만든 계정 표시. */
    public void markBootstrap() { this.joinedVia = JoinedVia.BOOTSTRAP; }

    private void requireStatus(MemberStatus expected, String message) {
        if (this.status != expected) throw new ConflictException(message);
    }
}
