package com.platform.orgservice.member;

import com.platform.orgservice.domain.Member;
import com.platform.orgservice.domain.MemberStatus;
import com.platform.orgservice.domain.PermAction;
import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.permission.PermissionFacade;
import com.platform.orgservice.repository.MemberRepository;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 전용(auth-server → org-service): 이 사람이 <b>활성 전역 관리자</b>인가(2026-10-24).
 *
 * <p>auth-server의 에이전트 페르소나 관리({@code /api/auth/agents/**})는 Keycloak realm 롤 ADMIN으로 열렸는데, 플랫폼 관리자의
 * 정본은 org GLOBAL ADMIN grant다(board·agent-service·wiki·alm이 같은 기준으로 옮겼다). auth-server는 gRPC 클라이언트가
 * 없으므로 초대·ensure와 같은 {@code X-Internal-Token} 내부 REST로 묻는다.
 *
 * <p>구성원 행이 없으면 {@code status=MISSING}, {@code globalAdmin=false}로 200을 준다 — 라우팅 404와 섞이지 않게.
 * {@code globalAdmin}은 ACTIVE일 때만 참이다(정지·퇴사·승인 대기는 grant가 남아 있어도 거짓 — CheckPermission과 같은 기준).
 */
@Hidden
@RestController
@RequiredArgsConstructor
public class InternalMemberAuthzController {

    private final MemberRepository members;
    private final PermissionFacade permissions;

    @GetMapping("/internal/org/members/{id}/authz")
    public AuthzResponse authz(@PathVariable long id) {
        MemberStatus status = members.findById(id).map(Member::getStatus).orElse(null);
        boolean admin = status == MemberStatus.ACTIVE
                && permissions.check(id, ResourceKind.GLOBAL, "", PermAction.ADMIN).allowed();
        return new AuthzResponse(id, status == null ? "MISSING" : status.name(), admin);
    }

    /** {@code status}: MISSING | PENDING | ACTIVE | SUSPENDED | DEACTIVATED */
    public record AuthzResponse(long memberId, String status, boolean globalAdmin) {}
}
