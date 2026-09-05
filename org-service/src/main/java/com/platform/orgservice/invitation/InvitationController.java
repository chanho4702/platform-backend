package com.platform.orgservice.invitation;

import com.platform.orgservice.common.PageResponse;
import com.platform.orgservice.invitation.dto.InvitationCreateRequest;
import com.platform.orgservice.invitation.dto.InvitationResponse;
import com.platform.orgservice.member.dto.MemberEventResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/org/invitations")
@RequiredArgsConstructor
public class InvitationController {

    private final InvitationService invitations;

    /**
     * 초대 생성. 응답의 {@code inviteUrl}은 <b>이 응답에서만</b> 볼 수 있다 —
     * 토큰 원문을 저장하지 않으므로 목록에서는 되살릴 수 없고, 다시 필요하면 재발송이 새 링크를 만든다.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public List<InvitationResponse> create(@AuthenticationPrincipal Jwt jwt,
                                           @Valid @RequestBody InvitationCreateRequest req) {
        return invitations.create(userId(jwt), req);
    }

    @GetMapping
    public PageResponse<InvitationResponse> list(@AuthenticationPrincipal Jwt jwt,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String q,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return invitations.list(userId(jwt), InvitationService.parseStatus(status), q, page, size);
    }

    /** 새 토큰·새 만료. {@code mail=false}면 메일은 보내지 않고 링크만 다시 받는다(링크 복사용). */
    @PostMapping("/{id}/resend")
    public InvitationResponse resend(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                     @RequestParam(defaultValue = "true") boolean mail) {
        return invitations.resend(userId(jwt), id, mail);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        invitations.revoke(userId(jwt), id);
    }

    @GetMapping("/{id}/events")
    public List<MemberEventResponse> events(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return invitations.events(userId(jwt), id);
    }

    private static long userId(Jwt jwt) { return Long.parseLong(jwt.getSubject()); }
}
