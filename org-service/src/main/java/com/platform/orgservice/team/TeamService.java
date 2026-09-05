package com.platform.orgservice.team;

import com.platform.common.error.ForbiddenException;
import com.platform.common.error.NotFoundException;
import com.platform.orgservice.domain.Member;
import com.platform.orgservice.domain.MemberEventType;
import com.platform.orgservice.domain.PermAction;
import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.domain.Team;
import com.platform.orgservice.domain.TeamMember;
import com.platform.orgservice.domain.TeamRole;
import com.platform.orgservice.member.MemberEventRecorder;
import com.platform.orgservice.permission.PermissionFacade;
import com.platform.orgservice.repository.MemberRepository;
import com.platform.orgservice.repository.TeamMemberRepository;
import com.platform.orgservice.repository.TeamRepository;
import com.platform.orgservice.team.dto.TeamCreateRequest;
import com.platform.orgservice.team.dto.TeamMemberResponse;
import com.platform.orgservice.team.dto.TeamResponse;
import com.platform.orgservice.team.dto.TeamUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional
public class TeamService {

    private final TeamRepository teams;
    private final TeamMemberRepository teamMembers;
    private final MemberRepository members;
    private final PermissionFacade permissions;
    private final MemberEventRecorder events;

    /**
     * 팀 목록. {@code memberCount}는 한 번의 group by로 붙이고, {@code myRole}은 호출자의 소속만 읽는다 —
     * 팀마다 구성원을 열면 목록 한 번에 수십 번의 쿼리가 나간다.
     */
    @Transactional(readOnly = true)
    public List<TeamResponse> list(long actorId, String q) {
        List<Team> found = (q == null || q.isBlank())
                ? teams.findAll()
                : teams.findByNameContainingIgnoreCase(q.trim());
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : teamMembers.countByTeam()) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        Map<Long, TeamRole> myRoles = new HashMap<>();
        teamMembers.findByMemberId(actorId).forEach(tm -> myRoles.put(tm.getTeamId(), tm.getRole()));
        return found.stream()
                .map(t -> TeamResponse.of(t, counts.getOrDefault(t.getId(), 0L), myRoles.get(t.getId())))
                .toList();
    }

    /**
     * 팀원 목록 — 팀 목록이 열려 있듯 이것도 인증만으로 볼 수 있다(W23). 누가 어느 팀인지는
     * 조직도이고, 권한 부여 화면이 팀을 고르려면 구성원을 알아야 한다.
     */
    @Transactional(readOnly = true)
    public List<TeamMemberResponse> members(long teamId) {
        if (!teams.existsById(teamId)) throw new NotFoundException("팀 없음: " + teamId);
        List<TeamMember> memberships = teamMembers.findByTeamId(teamId);
        // 팀원마다 멤버를 따로 열면 큰 팀 하나에 수십 번의 쿼리가 나간다.
        Map<Long, Member> byId = new HashMap<>();
        members.findAllById(memberships.stream().map(TeamMember::getMemberId).toList())
                .forEach(m -> byId.put(m.getId(), m));
        return memberships.stream()
                .map(tm -> {
                    Member member = byId.get(tm.getMemberId());
                    return new TeamMemberResponse(
                            tm.getMemberId(),
                            member == null ? "사용자 #" + tm.getMemberId() : member.getDisplayName(),
                            member == null ? null : member.getEmail(),
                            tm.getRole().name());
                })
                .toList();
    }

    public TeamResponse create(long actorId, TeamCreateRequest req) {
        permissions.requireGlobalAdmin(actorId);
        rejectEveryoneName(req.name());
        if (teams.existsByName(req.name())) throw new IllegalArgumentException("이미 존재하는 팀 이름: " + req.name());
        try {
            return view(teams.saveAndFlush(Team.of(req.name(), req.description())), actorId);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("이미 존재하는 팀 이름: " + req.name());
        }
    }

    public TeamResponse update(long actorId, long teamId, TeamUpdateRequest req) {
        permissions.requireGlobalAdmin(actorId);
        Team team = load(teamId);
        rejectIfEveryone(team, "전체 구성원 팀은 이름을 바꿀 수 없습니다");
        rejectEveryoneName(req.name());
        team.update(req.name(), req.description());
        try {
            teams.flush();
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("이미 존재하는 팀 이름: " + req.name());
        }
        return view(team, actorId);
    }

    public void delete(long actorId, long teamId) {
        permissions.requireGlobalAdmin(actorId);
        Team team = load(teamId);
        rejectIfEveryone(team, "전체 구성원 팀은 삭제할 수 없습니다");
        teams.deleteById(teamId);
    }

    public void addMember(long actorId, long teamId, long memberId, TeamRole role) {
        Team team = load(teamId);
        rejectIfEveryone(team, "전체 구성원 팀은 자동으로 소속되므로 직접 추가할 수 없습니다");
        requireTeamManager(actorId, teamId);
        if (!members.existsById(memberId)) throw new NotFoundException("멤버 없음: " + memberId);
        teamMembers.findByTeamIdAndMemberId(teamId, memberId).ifPresentOrElse(
                tm -> { /* 이미 팀원 — 멱등 */ },
                () -> {
                    teamMembers.save(TeamMember.of(teamId, memberId, role));
                    events.member(memberId, MemberEventType.TEAM_ADDED, actorId, "팀 합류: " + team.getName());
                });
    }

    public void removeMember(long actorId, long teamId, long memberId) {
        Team team = load(teamId);
        rejectIfEveryone(team, "전체 구성원 팀에서는 직접 제외할 수 없습니다");
        requireTeamManager(actorId, teamId);
        teamMembers.findByTeamIdAndMemberId(teamId, memberId).ifPresent(tm -> {
            teamMembers.delete(tm);
            events.member(memberId, MemberEventType.TEAM_REMOVED, actorId, "팀 제외: " + team.getName());
        });
    }

    /** 리더 지정·해제. 리더도 자기 팀에서 할 수 있다 — 팀 운영을 전역 관리자에게 매번 물으면 팀이 굳는다. */
    public TeamMemberResponse changeMemberRole(long actorId, long teamId, long memberId, TeamRole role) {
        Team team = load(teamId);
        rejectIfEveryone(team, "전체 구성원 팀에는 역할이 없습니다");
        requireTeamManager(actorId, teamId);
        TeamMember membership = teamMembers.findByTeamIdAndMemberId(teamId, memberId)
                .orElseThrow(() -> new NotFoundException("팀원 없음: " + memberId));
        membership.changeRole(role);
        Member member = members.findById(memberId).orElse(null);
        return new TeamMemberResponse(memberId,
                member == null ? "사용자 #" + memberId : member.getDisplayName(),
                member == null ? null : member.getEmail(),
                role.name());
    }

    /** 전역 관리자이거나 그 팀의 리더. 팀 생성·삭제·이름 변경은 여전히 전역 관리자만이다. */
    private void requireTeamManager(long actorId, long teamId) {
        if (permissions.check(actorId, ResourceKind.GLOBAL, "", PermAction.ADMIN).allowed()) return;
        boolean lead = teamMembers.findByTeamIdAndMemberId(teamId, actorId)
                .map(tm -> tm.getRole() == TeamRole.LEAD)
                .orElse(false);
        if (!lead) throw new ForbiddenException("이 팀을 관리할 수 없습니다");
    }

    private Team load(long teamId) {
        return teams.findById(teamId).orElseThrow(() -> new NotFoundException("팀 없음: " + teamId));
    }

    private static void rejectIfEveryone(Team team, String message) {
        if (team.isEveryone()) throw new IllegalArgumentException(message);
    }

    /** 이름으로 "전체 구성원"을 흉내 내면 화면에서 두 팀을 구분할 수 없다. */
    private static void rejectEveryoneName(String name) {
        if (name != null && name.trim().toLowerCase(Locale.ROOT)
                .equals(Team.EVERYONE_NAME.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("전체 구성원은 예약된 팀 이름입니다");
        }
    }

    private TeamResponse view(Team team, long actorId) {
        long count = teamMembers.findByTeamId(team.getId()).size();
        TeamRole myRole = teamMembers.findByTeamIdAndMemberId(team.getId(), actorId)
                .map(TeamMember::getRole).orElse(null);
        return TeamResponse.of(team, count, myRole);
    }
}
