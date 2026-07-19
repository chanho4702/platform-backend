package com.platform.orgservice.grant;

import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.grant.dto.GrantCreateRequest;
import com.platform.orgservice.grant.dto.GrantDetailResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/org/grants")
@RequiredArgsConstructor
public class GrantController {

    private final GrantService grants;

    @GetMapping
    public List<GrantDetailResponse> listByResource(@AuthenticationPrincipal Jwt jwt,
                                                    @RequestParam ResourceKind resourceType,
                                                    @RequestParam(required = false) String resourceId) {
        return grants.listByResource(userId(jwt), resourceType, resourceId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GrantDetailResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody GrantCreateRequest req) {
        return grants.create(userId(jwt), req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        grants.delete(userId(jwt), id);
    }

    private static long userId(Jwt jwt) { return Long.parseLong(jwt.getSubject()); }
}
