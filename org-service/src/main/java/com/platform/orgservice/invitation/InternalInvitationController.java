package com.platform.orgservice.invitation;

import com.platform.common.error.NotFoundException;
import com.platform.orgservice.domain.Invitation;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 서비스 간 전용(auth-server → org-service). 게이트웨이는 {@code /internal/**}을 라우팅하지 않고,
 * {@link com.platform.orgservice.security.InternalTokenFilter}가 {@code X-Internal-Token}을 검사한다.
 *
 * <p>여기에 사용자 JWT는 오지 않는다 — 초대 확인·수락은 <b>로그인 도중</b>에 일어나므로
 * 아직 우리 토큰이 없다. 그래서 이 두 개만 따로 열고, 나머지 초대 API는 전부 사용자 인증 경로에 둔다.
 *
 * <p>{@code @Hidden}: 내부 전용이라 OpenAPI 스펙에 싣지 않는다. {@code springdoc.paths-to-match}가
 * 이미 잘라 내지만, 그 설정이 넓어져도 이 컨트롤러만은 새지 않게 코드에도 표시해 둔다.
 */
@Hidden
@RestController
@RequestMapping("/internal/org/invitations")
@RequiredArgsConstructor
public class InternalInvitationController {

    private final InvitationService invitations;

    /** 토큰 유효성만. 유효하지 않으면 404 — 상태를 자세히 알려 주면 토큰을 긁는 데 쓰인다. */
    @GetMapping("/by-token/{token}")
    public TokenView byToken(@PathVariable String token) {
        Invitation invitation = invitations.findLiveByToken(token)
                .orElseThrow(() -> new NotFoundException("유효하지 않은 초대입니다"));
        return new TokenView(invitation.getEmail(), invitation.getStatus().name(), invitation.getExpiresAt());
    }

    /** 로그인 성공 직후 호출. 실패해도 auth-server는 로그인을 계속한다(이메일 매칭 경로가 남아 있다). */
    @PostMapping("/accept")
    public InvitationService.AcceptOutcome accept(@Valid @RequestBody AcceptRequest req) {
        return invitations.acceptByToken(req.token(), req.memberId(), req.email(), req.displayName());
    }

    public record TokenView(String email, String status, Instant expiresAt) {}

    public record AcceptRequest(@NotBlank String token, @NotNull Long memberId,
                                @NotBlank String email, String displayName) {}
}
