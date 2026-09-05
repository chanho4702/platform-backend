package com.platform.orgservice.member;

import com.platform.orgservice.member.dto.MemberResponse;
import com.platform.orgservice.profile.AvatarDirectory;
import com.platform.orgservice.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/org/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberRepository members;
    private final AvatarDirectory avatars;

    /**
     * 멤버 목록 — 팀원 선택 UI용. M 규모(수백 명)라 페이지네이션 없이 전체 반환.
     * 아바타는 프로필 테이블을 한 번에 읽어 붙인다(멤버마다 조회하면 목록 한 번에 수백 쿼리다).
     */
    @GetMapping
    public List<MemberResponse> list() {
        var avatarByMember = avatars.withAvatar();
        return members.findAll().stream()
                .map(m -> MemberResponse.from(m, avatarByMember.get(m.getId())))
                .toList();
    }
}
