package com.platform.orgservice.team;

import com.platform.orgservice.domain.TeamRole;
import com.platform.orgservice.team.dto.TeamCreateRequest;
import com.platform.orgservice.team.dto.TeamMemberResponse;
import com.platform.orgservice.team.dto.TeamResponse;
import com.platform.orgservice.team.dto.TeamUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/org/teams")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teams;

    @GetMapping
    public List<TeamResponse> list() {
        return teams.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TeamResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TeamCreateRequest req) {
        return teams.create(userId(jwt), req);
    }

    @PutMapping("/{id}")
    public TeamResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                               @Valid @RequestBody TeamUpdateRequest req) {
        return teams.update(userId(jwt), id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        teams.delete(userId(jwt), id);
    }

    @GetMapping("/{id}/members")
    public List<TeamMemberResponse> members(@PathVariable Long id) {
        return teams.members(id);
    }

    @PutMapping("/{id}/members/{memberId}")
    public void addMember(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                          @PathVariable Long memberId, @RequestParam(defaultValue = "MEMBER") TeamRole role) {
        teams.addMember(userId(jwt), id, memberId, role);
    }

    @DeleteMapping("/{id}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @PathVariable Long memberId) {
        teams.removeMember(userId(jwt), id, memberId);
    }

    private static long userId(Jwt jwt) { return Long.parseLong(jwt.getSubject()); }
}
