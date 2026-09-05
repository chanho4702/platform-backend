package com.platform.orgservice.repository;

import com.platform.orgservice.domain.MemberEvent;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MemberEventRepository extends JpaRepository<MemberEvent, Long> {

    /** 최신이 먼저. id를 2차 기준으로 두어 같은 시각의 기록도 순서가 흔들리지 않는다(grant_audit과 같은 규칙). */
    @Query("""
            select e from MemberEvent e
             where e.memberId = :memberId
             order by e.createdAt desc, e.id desc
            """)
    List<MemberEvent> findByMember(@Param("memberId") Long memberId, Limit limit);

    @Query("""
            select e from MemberEvent e
             where e.invitationId = :invitationId
             order by e.createdAt desc, e.id desc
            """)
    List<MemberEvent> findByInvitation(@Param("invitationId") Long invitationId, Limit limit);
}
