package com.platform.orgservice.team;

import com.platform.orgservice.domain.TeamRole;
import com.platform.orgservice.team.dto.TeamCreateRequest;
import com.platform.orgservice.team.dto.TeamMemberResponse;
import com.platform.orgservice.team.dto.TeamMemberRoleRequest;
import com.platform.orgservice.team.dto.TeamResponse;
import com.platform.orgservice.team.dto.TeamUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Teams", description = "팀과 팀원 — 팀은 권한(grant)의 주체가 될 수 있다. 변경은 전역 관리자 또는 그 팀 리더만 가능하다.")
@RestController
@RequestMapping("/api/org/teams")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teams;

    @Operation(summary = "팀 목록 조회 — myRole은 호출자가 그 팀 소속이 아니면 null")
    @GetMapping
    public List<TeamResponse> list(@AuthenticationPrincipal Jwt jwt,
                                   @Parameter(description = "팀 이름 부분 검색어")
                                   @RequestParam(required = false) String q) {
        return teams.list(userId(jwt), q);
    }

    @Operation(summary = "팀 생성")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TeamResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TeamCreateRequest req) {
        return teams.create(userId(jwt), req);
    }

    @Operation(summary = "팀 이름·설명 수정")
    @PutMapping("/{id}")
    public TeamResponse update(@AuthenticationPrincipal Jwt jwt,
                               @Parameter(description = "팀 id") @PathVariable Long id,
                               @Valid @RequestBody TeamUpdateRequest req) {
        return teams.update(userId(jwt), id, req);
    }

    @Operation(summary = "팀 삭제")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt,
                       @Parameter(description = "팀 id") @PathVariable Long id) {
        teams.delete(userId(jwt), id);
    }

    @Operation(summary = "팀원 목록 조회 — 이름과 이메일을 함께 준다")
    @GetMapping("/{id}/members")
    public List<TeamMemberResponse> members(@Parameter(description = "팀 id") @PathVariable Long id) {
        return teams.members(id);
    }

    @Operation(summary = "팀원 추가 — 이미 소속이면 역할만 갱신된다")
    @PutMapping("/{id}/members/{memberId}")
    public void addMember(@AuthenticationPrincipal Jwt jwt,
                          @Parameter(description = "팀 id") @PathVariable Long id,
                          @Parameter(description = "추가할 멤버 id") @PathVariable Long memberId,
                          @Parameter(description = "팀 내 역할 — LEAD | MEMBER")
                          @RequestParam(defaultValue = "MEMBER") TeamRole role) {
        teams.addMember(userId(jwt), id, memberId, role);
    }

    /** 리더 지정·해제. 팀원 추가·제거와 같은 권한(전역 관리자 또는 그 팀 리더). */
    @Operation(summary = "팀원 역할 변경 — 리더 지정·해제")
    @PatchMapping("/{id}/members/{memberId}")
    public TeamMemberResponse changeMemberRole(@AuthenticationPrincipal Jwt jwt,
                                               @Parameter(description = "팀 id") @PathVariable Long id,
                                               @Parameter(description = "대상 멤버 id") @PathVariable Long memberId,
                                               @RequestBody TeamMemberRoleRequest req) {
        return teams.changeMemberRole(userId(jwt), id, memberId, req.role());
    }

    @Operation(summary = "팀원 제외")
    @DeleteMapping("/{id}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal Jwt jwt,
                             @Parameter(description = "팀 id") @PathVariable Long id,
                             @Parameter(description = "제외할 멤버 id") @PathVariable Long memberId) {
        teams.removeMember(userId(jwt), id, memberId);
    }

    private static long userId(Jwt jwt) { return Long.parseLong(jwt.getSubject()); }
}
