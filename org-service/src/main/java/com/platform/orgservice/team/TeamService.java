package com.platform.orgservice.team;

import com.platform.orgservice.common.NotFoundException;
import com.platform.orgservice.domain.Team;
import com.platform.orgservice.domain.TeamMember;
import com.platform.orgservice.domain.TeamRole;
import com.platform.orgservice.permission.PermissionFacade;
import com.platform.orgservice.repository.MemberRepository;
import com.platform.orgservice.repository.TeamMemberRepository;
import com.platform.orgservice.repository.TeamRepository;
import com.platform.orgservice.team.dto.TeamCreateRequest;
import com.platform.orgservice.team.dto.TeamResponse;
import com.platform.orgservice.team.dto.TeamUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class TeamService {

    private final TeamRepository teams;
    private final TeamMemberRepository teamMembers;
    private final MemberRepository members;
    private final PermissionFacade permissions;

    @Transactional(readOnly = true)
    public List<TeamResponse> list() {
        return teams.findAll().stream().map(TeamResponse::from).toList();
    }

    public TeamResponse create(long actorId, TeamCreateRequest req) {
        permissions.requireGlobalAdmin(actorId);
        if (teams.existsByName(req.name())) throw new IllegalArgumentException("이미 존재하는 팀 이름: " + req.name());
        try {
            return TeamResponse.from(teams.saveAndFlush(Team.of(req.name(), req.description())));
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("이미 존재하는 팀 이름: " + req.name());
        }
    }

    public TeamResponse update(long actorId, long teamId, TeamUpdateRequest req) {
        permissions.requireGlobalAdmin(actorId);
        Team team = teams.findById(teamId).orElseThrow(() -> new NotFoundException("팀 없음: " + teamId));
        team.update(req.name(), req.description());
        try {
            teams.flush();
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("이미 존재하는 팀 이름: " + req.name());
        }
        return TeamResponse.from(team);
    }

    public void delete(long actorId, long teamId) {
        permissions.requireGlobalAdmin(actorId);
        if (!teams.existsById(teamId)) throw new NotFoundException("팀 없음: " + teamId);
        teams.deleteById(teamId);
    }

    public void addMember(long actorId, long teamId, long memberId, TeamRole role) {
        permissions.requireGlobalAdmin(actorId);
        if (!teams.existsById(teamId)) throw new NotFoundException("팀 없음: " + teamId);
        if (!members.existsById(memberId)) throw new NotFoundException("멤버 없음: " + memberId);
        teamMembers.findByTeamIdAndMemberId(teamId, memberId).ifPresentOrElse(
                tm -> { /* 이미 팀원 — 멱등 */ },
                () -> teamMembers.save(TeamMember.of(teamId, memberId, role)));
    }

    public void removeMember(long actorId, long teamId, long memberId) {
        permissions.requireGlobalAdmin(actorId);
        teamMembers.findByTeamIdAndMemberId(teamId, memberId)
                .ifPresent(teamMembers::delete);
    }
}
