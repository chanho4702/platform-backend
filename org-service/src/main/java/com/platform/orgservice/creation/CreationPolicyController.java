package com.platform.orgservice.creation;

import com.platform.orgservice.creation.dto.CapabilitiesResponse;
import com.platform.orgservice.creation.dto.CreationPolicyResponse;
import com.platform.orgservice.creation.dto.CreationPolicyUpdateRequest;
import com.platform.orgservice.domain.ResourceKind;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 스페이스·프로젝트 생성 가능 대상. 설정은 전역 관리자 전용(서비스가 판정)이고, 내 가능 여부는 누구나 읽는다 —
 * {@code /api/org/me/**}는 계정 상태와 무관하게 열려 있고, 승인 대기·정지면 둘 다 false다.
 */
@Tag(name = "Creation", description = "스페이스·프로젝트를 새로 만들 수 있는 사람 — 전역 설정과 내 가능 여부")
@RestController
@RequiredArgsConstructor
public class CreationPolicyController {

    private final CreationPolicyService service;

    @Operation(summary = "생성 가능 대상 설정 조회(전역 관리자)")
    @GetMapping("/api/org/settings/creation")
    public CreationPolicyResponse get(@AuthenticationPrincipal Jwt jwt) {
        return service.view(userId(jwt));
    }

    @Operation(summary = "생성 가능 대상 설정 저장(전역 관리자) — space·project 중 생략한 쪽은 그대로")
    @PutMapping("/api/org/settings/creation")
    public CreationPolicyResponse put(@AuthenticationPrincipal Jwt jwt,
                                      @RequestBody CreationPolicyUpdateRequest req) {
        return service.update(userId(jwt), req);
    }

    @Operation(summary = "내가 지금 만들 수 있는 것 — 스페이스·프로젝트 만들기 버튼 표시용")
    @GetMapping("/api/org/me/capabilities")
    public CapabilitiesResponse capabilities(@AuthenticationPrincipal Jwt jwt) {
        long id = userId(jwt);
        return new CapabilitiesResponse(service.canCreate(id, ResourceKind.SPACE).allowed(),
                service.canCreate(id, ResourceKind.PROJECT).allowed());
    }

    private static long userId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
