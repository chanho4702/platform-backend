package com.platform.orgservice.repository;

import com.platform.orgservice.domain.TeamMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TeamMemberRepository extends JpaRepository<TeamMember, Long> {

    @Query("select tm.teamId from TeamMember tm where tm.memberId = :memberId")
    List<Long> findTeamIdsByMemberId(@Param("memberId") Long memberId);

    Optional<TeamMember> findByTeamIdAndMemberId(Long teamId, Long memberId);

    List<TeamMember> findByTeamId(Long teamId);

    List<TeamMember> findByMemberId(Long memberId);

    /** 팀 목록의 memberCount — 팀마다 구성원을 열지 않으려고 한 번에 센다(N+1 방지). */
    @Query("select tm.teamId, count(tm) from TeamMember tm group by tm.teamId")
    List<Object[]> countByTeam();

    long countByTeamIdAndRole(Long teamId, com.platform.orgservice.domain.TeamRole role);
}
