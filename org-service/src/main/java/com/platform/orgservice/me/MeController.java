package com.platform.orgservice.me;

import com.platform.orgservice.me.dto.GrantResponse;
import com.platform.orgservice.permission.PermissionFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Me", description = "내 정보 — 프로필·소속 팀·권한. 승인 대기 계정도 이 경로만은 읽을 수 있다.")
@RestController
@RequestMapping("/api/org/me")
@RequiredArgsConstructor
public class MeController {

    private final PermissionFacade permissions;

    /** 내 grant 목록 — 프론트 메뉴/버튼 제어용. */
    @Operation(summary = "내 권한(grant) 목록 조회 — 프론트 메뉴·버튼 제어용")
    @GetMapping("/permissions")
    public List<GrantResponse> myPermissions(@AuthenticationPrincipal Jwt jwt) {
        long userId = Long.parseLong(jwt.getSubject());
        return permissions.grantsOf(userId, null).stream().map(GrantResponse::from).toList();
    }
}
