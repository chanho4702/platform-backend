package com.platform.orgservice.grant;

import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.grant.dto.GrantAuditResponse;
import com.platform.orgservice.grant.dto.GrantCreateRequest;
import com.platform.orgservice.grant.dto.GrantDetailResponse;
import com.platform.orgservice.grant.dto.GrantRoleRequest;
import com.platform.orgservice.config.ConflictResponse;
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

@Tag(name = "Grants", description = "권한(grant) 원장 — 누가 어느 리소스에서 무엇을 할 수 있는지. 조회·변경 모두 그 리소스의 ADMIN만 가능하다.")
@RestController
@RequestMapping("/api/org/grants")
@RequiredArgsConstructor
public class GrantController {

    private final GrantService grants;

    @Operation(summary = "리소스의 권한 목록 조회")
    @GetMapping
    public List<GrantDetailResponse> listByResource(@AuthenticationPrincipal Jwt jwt,
                                                    @Parameter(description = "리소스 종류 — GLOBAL | SPACE | PROJECT 등")
                                                    @RequestParam ResourceKind resourceType,
                                                    @Parameter(description = "리소스 식별자. GLOBAL이면 비워 둔다.")
                                                    @RequestParam(required = false) String resourceId) {
        return grants.listByResource(userId(jwt), resourceType, resourceId);
    }

    /**
     * 권한 변경 이력. 조회 범위는 grant 목록과 같다(그 리소스의 ADMIN).
     *
     * 응답 모양을 wiki-backend의 감사 항목과 맞춰 뒀다 — 화면이 두 기록을 한 목록으로 합친다.
     */
    @Operation(summary = "리소스의 권한 변경 이력 조회")
    @GetMapping("/audit")
    public List<GrantAuditResponse> auditByResource(@AuthenticationPrincipal Jwt jwt,
                                                    @Parameter(description = "리소스 종류 — GLOBAL | SPACE | PROJECT 등")
                                                    @RequestParam ResourceKind resourceType,
                                                    @Parameter(description = "리소스 식별자. GLOBAL이면 비워 둔다.")
                                                    @RequestParam(required = false) String resourceId) {
        return grants.auditByResource(userId(jwt), resourceType, resourceId);
    }

    @Operation(summary = "권한 부여")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GrantDetailResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody GrantCreateRequest req) {
        return grants.create(userId(jwt), req);
    }

    /** 역할 변경. 마지막 전역 관리자의 강등은 409로 막힌다. */
    @Operation(summary = "권한 역할 변경 — 마지막 전역 관리자의 강등은 409")
    @ConflictResponse("마지막 전역 관리자는 내릴 수 없습니다.")
    @PatchMapping("/{id}")
    public GrantDetailResponse changeRole(@AuthenticationPrincipal Jwt jwt,
                                          @Parameter(description = "권한 행 id") @PathVariable Long id,
                                          @Valid @RequestBody GrantRoleRequest req) {
        return grants.changeRole(userId(jwt), id, req.role());
    }

    @Operation(summary = "권한 회수")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt,
                       @Parameter(description = "권한 행 id") @PathVariable Long id) {
        grants.delete(userId(jwt), id);
    }

    private static long userId(Jwt jwt) { return Long.parseLong(jwt.getSubject()); }
}
