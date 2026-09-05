package com.platform.orgservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 권한 부여·회수의 흔적(W23).
 *
 * 대상 이름을 함께 저장한다 — 사용자가 지워지거나 팀이 사라져도 기록은 읽혀야 하고, id만
 * 남기면 숫자만 보인다.
 */
@Entity
@Table(name = "grant_audit")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GrantAudit {

    public enum Action { GRANTED, CHANGED, REVOKED }

    private static final int MAX_LABEL = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private Long actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private Action action;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 10, updatable = false)
    private SubjectType subjectType;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private Long subjectId;

    @Column(name = "subject_label", nullable = false, length = MAX_LABEL, updatable = false)
    private String subjectLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 20, updatable = false)
    private ResourceKind resourceType;

    @Column(name = "resource_id", nullable = false, length = 100, updatable = false)
    private String resourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private GrantRole role;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static GrantAudit of(long actorId, Action action, GrantEntry grant, String subjectLabel) {
        GrantAudit a = new GrantAudit();
        a.actorId = actorId;
        a.action = action;
        a.subjectType = grant.getSubjectType();
        a.subjectId = grant.getSubjectId();
        a.subjectLabel = clamp(subjectLabel);
        a.resourceType = grant.getResourceType();
        a.resourceId = grant.getResourceId() == null ? "" : grant.getResourceId();
        a.role = grant.getRole();
        return a;
    }

    /** 이름이 길다고 권한 조작을 실패시키지 않는다 — 자른 흔적을 남기고 계속한다. */
    private static String clamp(String value) {
        String safe = value == null || value.isBlank() ? "(이름 없음)" : value;
        return safe.length() <= MAX_LABEL ? safe : safe.substring(0, MAX_LABEL - 1) + "…";
    }
}
