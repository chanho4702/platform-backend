package com.platform.orgservice.team;

import com.platform.orgservice.domain.Team;
import com.platform.orgservice.domain.TeamKind;
import com.platform.orgservice.domain.TeamMember;
import com.platform.orgservice.domain.TeamRole;
import com.platform.orgservice.repository.TeamMemberRepository;
import com.platform.orgservice.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "전체 구성원" 팀의 유일한 창구.
 *
 * <p>이 팀은 사용자가 관리하지 않는다 — 활성 사람 멤버가 되면 들어가고, 정지·비활성되면 빠진다.
 * 그래서 가입·탈퇴를 여기 한 곳에 모아 두고, 팀 관리 API는 이 팀을 400으로 거절한다.
 * 스페이스·프로젝트 ADMIN이 이 팀에 VIEWER를 주면 그것이 곧 "공개"다.
 */
@Service
@RequiredArgsConstructor
public class EveryoneTeamService {

    private final TeamRepository teams;
    private final TeamMemberRepository teamMembers;

    /** 없으면 만든다. Flyway V6가 시드하지만 테스트(H2·ddl-auto)에는 마이그레이션이 돌지 않는다. */
    @Transactional
    public Team ensure() {
        return teams.findFirstByKind(TeamKind.EVERYONE)
                .orElseGet(() -> {
                    try {
                        return teams.saveAndFlush(Team.everyone());
                    } catch (DataIntegrityViolationException e) {
                        // 같은 이름의 STANDARD 팀이 먼저 있거나 동시 생성 — 이미 있는 것을 쓴다
                        return teams.findFirstByKind(TeamKind.EVERYONE)
                                .orElseThrow(() -> e);
                    }
                });
    }

    /** 멱등 — 이미 들어 있으면 아무것도 하지 않는다. */
    @Transactional
    public void add(long memberId) {
        Team everyone = ensure();
        if (teamMembers.findByTeamIdAndMemberId(everyone.getId(), memberId).isEmpty()) {
            teamMembers.save(TeamMember.of(everyone.getId(), memberId, TeamRole.MEMBER));
        }
    }

    @Transactional
    public void remove(long memberId) {
        teams.findFirstByKind(TeamKind.EVERYONE).ifPresent(everyone ->
                teamMembers.findByTeamIdAndMemberId(everyone.getId(), memberId)
                        .ifPresent(teamMembers::delete));
    }
}
