package com.platform.orgservice.member;

import com.platform.orgservice.member.dto.AgentRegisterRequest;
import com.platform.orgservice.member.dto.MemberResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * agent-service가 ADMIN 사용자 JWT로 호출해 에이전트 페르소나를 org 멤버로 등록한다.
 * 로그인 없는 에이전트라 JIT mirror(MemberMirrorFilter)로는 생성되지 않는다 — 여기서 명시 등록한다.
 */
@Tag(name = "Members", description = "조직 멤버 — 로그인한 사람은 첫 요청에 자동으로 미러링되고, 이 API는 그 원장을 조회·승인·상태 전이한다.")
@RestController
@RequestMapping("/api/org/members/agents")
@RequiredArgsConstructor
public class AgentMemberController {

    private final MemberService members;

    @Operation(summary = "에이전트 페르소나를 멤버로 등록 — 전역 관리자 전용")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public MemberResponse register(@Valid @RequestBody AgentRegisterRequest req) {
        var member = members.registerAgent(req.id(), req.displayName(), req.email());
        return MemberResponse.from(member);
    }
}
