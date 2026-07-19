package com.platform.orgservice.member;

import com.platform.orgservice.member.dto.MemberResponse;
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

    /** 멤버 목록 — 팀원 선택 UI용. M 규모(수백 명)라 페이지네이션 없이 전체 반환. */
    @GetMapping
    public List<MemberResponse> list() {
        return members.findAll().stream().map(MemberResponse::from).toList();
    }
}
