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
}
