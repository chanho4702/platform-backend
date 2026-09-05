package com.platform.orgservice.repository;

import com.platform.orgservice.domain.InvitationTeam;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface InvitationTeamRepository extends JpaRepository<InvitationTeam, Long> {

    List<InvitationTeam> findByInvitationId(Long invitationId);

    /** 목록 응답이 초대마다 팀을 따로 열지 않도록 한 번에 읽는다(N+1 방지). */
    List<InvitationTeam> findByInvitationIdIn(Collection<Long> invitationIds);

    void deleteByInvitationId(Long invitationId);
}
