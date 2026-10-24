package com.platform.orgservice.member;

import com.platform.orgservice.domain.MemberStatus;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 전용(auth-server → org-service): 로그인 성공 순간 구성원 행을 보장한다(권한·계정 점검 Top 5-①).
 *
 * <p>왜 필요한가: CheckPermission은 이제 구성원 행이 없으면 PENDING으로 거부한다. 행은 지금까지 org REST 첫 호출
 * ({@code MemberMirrorFilter})에서만 생겼으므로, 로그인 직후 위키부터 연 사람은 행이 없어 막힌다. 로그인 순간에
 * 같은 미러링({@link MemberService#mirror})을 한 번 태워 둔다 — 없으면 PENDING으로 만들고, 이메일이 일치하는 초대
 * (신뢰 provider)·부트스트랩 관리자면 그 자리에서 활성화하며, 이미 있는 행은 상태를 바꾸지 않는다(멱등).
 *
 * <p>인증은 {@link com.platform.common.security.InternalTokenFilter}의 {@code X-Internal-Token}뿐이다(사용자 JWT 없음).
 * 실패해도 auth-server는 로그인을 계속한다 — 다음 org REST 호출의 미러링이 같은 일을 한다.
 */
@Hidden
@RestController
@RequestMapping("/internal/org/members/ensure")
@RequiredArgsConstructor
public class InternalMemberEnsureController {

    private final MemberService members;

    @PostMapping
    public EnsureResponse ensure(@Valid @RequestBody EnsureRequest req) {
        MemberStatus status = members.ensure(req.memberId(), blankToNull(req.displayName()),
                blankToNull(req.email()), blankToNull(req.provider()));
        return new EnsureResponse(req.memberId(), status.name());
    }

    /** {@code provider}는 auth-server users.provider(GOOGLE·KEYCLOAK) — 이메일 대조 초대 소진의 신뢰 판단에 쓴다. */
    public record EnsureRequest(@NotNull(message = "memberId가 필요합니다") @Positive Long memberId,
                                String email, String displayName, String provider) {}

    /** {@code status}: PENDING | ACTIVE | SUSPENDED | DEACTIVATED */
    public record EnsureResponse(long memberId, String status) {}

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
