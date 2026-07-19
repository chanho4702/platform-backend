package com.platform.orgservice.me;

import com.platform.orgservice.me.dto.GrantResponse;
import com.platform.orgservice.permission.PermissionFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/org/me")
@RequiredArgsConstructor
public class MeController {

    private final PermissionFacade permissions;

    /** 내 grant 목록 — 프론트 메뉴/버튼 제어용. */
    @GetMapping("/permissions")
    public List<GrantResponse> myPermissions(@AuthenticationPrincipal Jwt jwt) {
        long userId = Long.parseLong(jwt.getSubject());
        return permissions.grantsOf(userId, null).stream().map(GrantResponse::from).toList();
    }
}
