package com.platform.orgservice.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "grant_entry",
        uniqueConstraints = @UniqueConstraint(columnNames = {"subject_type", "subject_id", "resource_type", "resource_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GrantEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false)
    private SubjectType subjectType;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false)
    private ResourceKind resourceType;

    @Column(name = "resource_id", nullable = false)
    private String resourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GrantRole role;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public static GrantEntry of(SubjectType subjectType, Long subjectId,
                                ResourceKind resourceType, String resourceId, GrantRole role) {
        GrantEntry g = new GrantEntry();
        g.subjectType = subjectType;
        g.subjectId = subjectId;
        g.resourceType = resourceType;
        g.resourceId = resourceId != null ? resourceId : "";
        g.role = role;
        return g;
    }

    public static GrantEntry globalAdmin(Long userId) {
        return of(SubjectType.USER, userId, ResourceKind.GLOBAL, "", GrantRole.ADMIN);
    }

    /** 부트스트랩 시드가 기존 grant를 승격할 때 사용. */
    public void changeRole(GrantRole role) { this.role = role; }
}
