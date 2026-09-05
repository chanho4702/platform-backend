package com.platform.orgservice.profile;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.function.Function;

/**
 * 멤버 목록 응답이 아바타를 붙일 때 쓰는 조회 창구. 프로필 테이블을 한 번에 읽어 멤버 id로 색인한다 —
 * 멤버마다 프로필을 따로 열면 목록 한 번에 수백 개의 쿼리가 나간다.
 */
@Component
@RequiredArgsConstructor
public class AvatarDirectory {

    private final MemberProfileRepository profiles;

    /** 아바타가 있는 멤버만 담긴 맵 — 없는 멤버는 키 자체가 없다 */
    @Transactional(readOnly = true)
    public Map<Long, MemberProfile> withAvatar() {
        return profiles.findByAvatarKeyIsNotNull().stream()
                .collect(java.util.stream.Collectors.toMap(MemberProfile::getMemberId, Function.identity()));
    }
}
