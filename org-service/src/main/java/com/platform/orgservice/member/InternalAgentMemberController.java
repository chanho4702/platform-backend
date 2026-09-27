package com.platform.orgservice.member;

import com.platform.orgservice.member.dto.MemberResponse;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 전용(agent-service → org-service) 에이전트 멤버 등록. 권한 판정은 호출측이 끝낸 뒤 온다 —
 * 여기는 {@link com.platform.orgservice.security.InternalTokenFilter}의 {@code X-Internal-Token}만 본다.
 *
 * <p>사용자 JWT가 없으므로 REACTIVATED 이력의 행위자는 본문의 {@code actorId}(호출측이 넣은 원 사용자 id)다.
 * 응답 shape은 {@code POST /api/org/members/agents}와 같다.
 *
 * <p>{@code @Hidden}: 내부 전용이라 OpenAPI 스펙에 싣지 않는다.
 */
@Hidden
@RestController
@RequestMapping("/internal/org/members/agents")
@RequiredArgsConstructor
public class InternalAgentMemberController {

    private final MemberService members;

    @PostMapping
    public MemberResponse register(@Valid @RequestBody InternalAgentRegisterRequest req) {
        return MemberResponse.from(members.registerAgent(req.actorId(), req.id(), req.displayName(), req.email()));
    }

    public record InternalAgentRegisterRequest(
            @NotNull(message = "에이전트 id가 필요합니다") Long id,
            @NotBlank(message = "표시 이름이 필요합니다") String displayName,
            String email,
            @NotNull(message = "행위자 id(actorId)가 필요합니다") Long actorId) {}
}
