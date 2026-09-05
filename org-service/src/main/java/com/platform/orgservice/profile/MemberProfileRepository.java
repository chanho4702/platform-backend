package com.platform.orgservice.profile;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MemberProfileRepository extends JpaRepository<MemberProfile, Long> {

    /** 아바타를 올린 멤버만 — 목록 응답이 한 번에 받아 멤버별 URL을 붙인다(N+1 방지) */
    List<MemberProfile> findByAvatarKeyIsNotNull();
}
