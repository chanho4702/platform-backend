package com.platform.orgservice.profile;

import com.platform.common.error.NotFoundException;
import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.domain.Team;
import com.platform.orgservice.domain.TeamMember;
import com.platform.orgservice.permission.PermissionFacade;
import com.platform.orgservice.profile.dto.MeResponse;
import com.platform.orgservice.repository.MemberRepository;
import com.platform.orgservice.repository.TeamMemberRepository;
import com.platform.orgservice.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 내 프로필. {@code /api/org/me/permissions}(MeController)와 경로 접두를 나누어 쓰되 파일을 분리한 것은
 * 아바타·프로필이 org의 권한 도메인과 다른 관심사이기 때문이다.
 *
 * <p>멤버 행은 MemberMirrorFilter가 인증 직후 JIT로 만들어 두므로 여기서는 이미 존재한다.
 * 이 경로만은 승인 대기·정지 계정도 지나간다 — 자기 상태를 못 읽으면 안내 화면조차 그릴 수 없다.
 */
@RestController
@RequiredArgsConstructor
public class MeProfileController {

    private final MemberRepository members;
    private final MemberProfileRepository profiles;
    private final TeamMemberRepository teamMembers;
    private final TeamRepository teams;
    private final PermissionFacade permissions;

    @GetMapping("/api/org/me")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        long memberId = AvatarController.memberId(jwt);
        var member = members.findById(memberId)
                .orElseThrow(() -> new NotFoundException("멤버를 찾을 수 없습니다"));
        return MeResponse.from(member, profiles.findById(memberId).orElse(null),
                globalRoles(memberId), teamMemberships(memberId));
    }

    /** GLOBAL grant의 역할 목록. 프론트는 여기에 "ADMIN"이 있는지로 관리자 화면을 연다. */
    private List<String> globalRoles(long memberId) {
        return permissions.grantsOf(memberId, ResourceKind.GLOBAL).stream()
                .map(GrantEntry::getRole)
                .distinct()
                .map(Enum::name)
                .toList();
    }

    private List<MeResponse.TeamMembership> teamMemberships(long memberId) {
        List<TeamMember> memberships = teamMembers.findByMemberId(memberId);
        Map<Long, Team> byId = new LinkedHashMap<>();
        teams.findAllById(memberships.stream().map(TeamMember::getTeamId).toList())
                .forEach(t -> byId.put(t.getId(), t));
        List<MeResponse.TeamMembership> views = new ArrayList<>();
        for (TeamMember tm : memberships) {
            Team team = byId.get(tm.getTeamId());
            if (team == null) continue;
            views.add(new MeResponse.TeamMembership(team.getId(), team.getName(),
                    team.getKind().name(), tm.getRole().name()));
        }
        return views;
    }
}
