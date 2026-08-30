package com.platform.orgservice.grant;

import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.grant.dto.GrantAuditResponse;
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

    /**
     * 권한 변경 이력. 조회 범위는 grant 목록과 같다(그 리소스의 ADMIN).
     *
     * 응답 모양을 wiki-backend의 감사 항목과 맞춰 뒀다 — 화면이 두 기록을 한 목록으로 합친다.
     */
    @GetMapping("/audit")
    public List<GrantAuditResponse> auditByResource(@AuthenticationPrincipal Jwt jwt,
                                                    @RequestParam ResourceKind resourceType,
                                                    @RequestParam(required = false) String resourceId) {
        return grants.auditByResource(userId(jwt), resourceType, resourceId);
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
