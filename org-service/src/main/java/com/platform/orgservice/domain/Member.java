package com.platform.orgservice.domain;

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

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public static Member of(Long id, String displayName, String email) {
        Member m = new Member();
        m.id = id;
        m.displayName = displayName != null ? displayName : "user-" + id;
        m.email = email;
        m.status = MemberStatus.ACTIVE;
        m.kind = MemberKind.HUMAN;
        return m;
    }

    /** agent-service가 등록하는 에이전트 페르소나. */
    public static Member agentOf(Long id, String displayName, String email) {
        Member m = new Member();
        m.id = id;
        m.displayName = displayName != null ? displayName : "agent-" + id;
        m.email = email;
        m.status = MemberStatus.ACTIVE;
        m.kind = MemberKind.AGENT;
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
}
