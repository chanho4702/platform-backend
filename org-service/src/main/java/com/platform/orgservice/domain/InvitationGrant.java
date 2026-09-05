package com.platform.orgservice.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 초대 수락 시 만들 grant. 표현은 grant_entry와 같다(GLOBAL이면 resourceId는 빈 문자열). */
@Entity
@Table(name = "invitation_grant",
        uniqueConstraints = @UniqueConstraint(columnNames = {"invitation_id", "scope", "resource_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InvitationGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invitation_id", nullable = false)
    private Long invitationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResourceKind scope;

    @Column(name = "resource_id", nullable = false, length = 100)
    private String resourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GrantRole role;

    public static InvitationGrant of(Long invitationId, ResourceKind scope, String resourceId, GrantRole role) {
        InvitationGrant g = new InvitationGrant();
        g.invitationId = invitationId;
        g.scope = scope;
        g.resourceId = (scope == ResourceKind.GLOBAL || resourceId == null) ? "" : resourceId;
        g.role = role;
        return g;
    }
}
