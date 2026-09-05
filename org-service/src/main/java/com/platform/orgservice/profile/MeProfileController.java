package com.platform.orgservice.profile;

import com.platform.common.error.NotFoundException;
import com.platform.orgservice.profile.dto.MeResponse;
import com.platform.orgservice.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 프로필. {@code /api/org/me/permissions}(MeController)와 경로 접두를 나누어 쓰되 파일을 분리한 것은
 * 아바타·프로필이 org의 권한 도메인과 다른 관심사이기 때문이다.
 *
 * 멤버 행은 MemberMirrorFilter가 인증 직후 JIT로 만들어 두므로 여기서는 이미 존재한다.
 */
@RestController
@RequiredArgsConstructor
public class MeProfileController {

    private final MemberRepository members;
    private final MemberProfileRepository profiles;

    @GetMapping("/api/org/me")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        long memberId = AvatarController.memberId(jwt);
        var member = members.findById(memberId)
                .orElseThrow(() -> new NotFoundException("멤버를 찾을 수 없습니다"));
        return MeResponse.from(member, profiles.findById(memberId).orElse(null));
    }
}
