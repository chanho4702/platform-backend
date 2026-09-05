package com.platform.orgservice.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 초대 수락 시 넣을 팀. 초대 시점에는 member가 없어 team_member를 미리 만들 수 없다. */
@Entity
@Table(name = "invitation_team",
        uniqueConstraints = @UniqueConstraint(columnNames = {"invitation_id", "team_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InvitationTeam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invitation_id", nullable = false)
    private Long invitationId;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TeamRole role;

    public static InvitationTeam of(Long invitationId, Long teamId, TeamRole role) {
        InvitationTeam it = new InvitationTeam();
        it.invitationId = invitationId;
        it.teamId = teamId;
        it.role = role == null ? TeamRole.MEMBER : role;
        return it;
    }
}
