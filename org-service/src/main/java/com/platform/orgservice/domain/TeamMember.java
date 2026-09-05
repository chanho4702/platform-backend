package com.platform.orgservice.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "team_member",
        uniqueConstraints = @UniqueConstraint(columnNames = {"team_id", "member_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TeamMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TeamRole role;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /** 리더 지정·해제(PATCH). 행을 지웠다 다시 만들면 합류 시각이 사라진다. */
    public void changeRole(TeamRole role) { this.role = role; }

    public static TeamMember of(Long teamId, Long memberId, TeamRole role) {
        TeamMember tm = new TeamMember();
        tm.teamId = teamId;
        tm.memberId = memberId;
        tm.role = role;
        return tm;
    }
}
