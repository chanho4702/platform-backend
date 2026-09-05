package com.platform.orgservice.invitation;

import com.platform.orgservice.common.PageResponse;
import com.platform.orgservice.invitation.dto.InvitationCreateRequest;
import com.platform.orgservice.invitation.dto.InvitationResponse;
import com.platform.orgservice.member.dto.MemberEventResponse;
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

@Tag(name = "Invitations", description = "초대 — 이메일로 사람을 조직에 들인다. 팀·권한 프리셋을 함께 담아 두면 수락 시점에 적용된다.")
@RestController
@RequestMapping("/api/org/invitations")
@RequiredArgsConstructor
public class InvitationController {

    private final InvitationService invitations;

    /**
     * 초대 생성. 응답의 {@code inviteUrl}은 <b>이 응답에서만</b> 볼 수 있다 —
     * 토큰 원문을 저장하지 않으므로 목록에서는 되살릴 수 없고, 다시 필요하면 재발송이 새 링크를 만든다.
     */
    @Operation(summary = "초대 생성 — 이메일 여러 개를 한 번에. inviteUrl은 이 응답에서만 볼 수 있다")
    @ConflictResponse("이미 활성이거나 정지된 계정의 이메일입니다.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public List<InvitationResponse> create(@AuthenticationPrincipal Jwt jwt,
                                           @Valid @RequestBody InvitationCreateRequest req) {
        return invitations.create(userId(jwt), req);
    }

    @Operation(summary = "초대 목록 조회 — 목록의 inviteUrl은 항상 null이다")
    @GetMapping
    public PageResponse<InvitationResponse> list(@AuthenticationPrincipal Jwt jwt,
                                                 @Parameter(description = "상태 필터 — PENDING | ACCEPTED | REVOKED | EXPIRED. 미지정이면 전부.")
                                                 @RequestParam(required = false) String status,
                                                 @Parameter(description = "이메일 부분 검색어")
                                                 @RequestParam(required = false) String q,
                                                 @Parameter(description = "0부터 세는 페이지 번호")
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @Parameter(description = "페이지 크기")
                                                 @RequestParam(defaultValue = "20") int size) {
        return invitations.list(userId(jwt), InvitationService.parseStatus(status), q, page, size);
    }

    /** 새 토큰·새 만료. {@code mail=false}면 메일은 보내지 않고 링크만 다시 받는다(링크 복사용). */
    @Operation(summary = "초대 재발송 — 새 토큰·새 만료로 링크를 다시 만든다")
    @ConflictResponse("이미 수락된 초대입니다.")
    @PostMapping("/{id}/resend")
    public InvitationResponse resend(@AuthenticationPrincipal Jwt jwt,
                                     @Parameter(description = "초대 id") @PathVariable Long id,
                                     @Parameter(description = "false면 메일을 보내지 않고 링크만 응답에 담는다(링크 복사용)")
                                     @RequestParam(defaultValue = "true") boolean mail) {
        return invitations.resend(userId(jwt), id, mail);
    }

    @Operation(summary = "초대 철회 — 대기 중인 초대만 철회할 수 있다")
    @ConflictResponse("대기 중인 초대만 철회할 수 있습니다.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal Jwt jwt,
                       @Parameter(description = "초대 id") @PathVariable Long id) {
        invitations.revoke(userId(jwt), id);
    }

    @Operation(summary = "초대 이력 조회 — 발송·재발송·수락·철회")
    @GetMapping("/{id}/events")
    public List<MemberEventResponse> events(@AuthenticationPrincipal Jwt jwt,
                                            @Parameter(description = "초대 id") @PathVariable Long id) {
        return invitations.events(userId(jwt), id);
    }

    private static long userId(Jwt jwt) { return Long.parseLong(jwt.getSubject()); }
}
