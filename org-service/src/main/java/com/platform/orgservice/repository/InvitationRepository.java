package com.platform.orgservice.repository;

import com.platform.orgservice.domain.Invitation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 목록 질의는 {@link JpaSpecificationExecutor}로 조립한다 — 선택 조건 셋(status·q·invitedBy)을
 * {@code :x is null or ...}로 쓰면 H2에서 enum 바인딩이 흔들린다(GrantEntryRepository 주석의 같은 이유).
 */
public interface InvitationRepository extends JpaRepository<Invitation, Long>,
        JpaSpecificationExecutor<Invitation> {

    Optional<Invitation> findByTokenHash(String tokenHash);

    /** 관리자 대시보드 통계 — 아직 살아 있는 초대 수(PENDING). */
    long countByStatus(com.platform.orgservice.domain.InvitationStatus status);

    /**
     * 이 이메일의 살아 있는 초대. 여러 건이 남아 있어도 <b>가장 최근 것만</b> 유효하다 —
     * 생성 시점에 이전 것을 EXPIRED로 눕히지만, 그래도 하나로 고정해 판단이 흔들리지 않게 한다.
     */
    @Query("""
            select i from Invitation i
             where i.emailNorm = :emailNorm and i.status = com.platform.orgservice.domain.InvitationStatus.PENDING
             order by i.createdAt desc, i.id desc
            """)
    List<Invitation> findPendingByEmailNorm(@Param("emailNorm") String emailNorm, Pageable pageable);

    @Query("""
            select i from Invitation i
             where i.emailNorm = :emailNorm and i.status = com.platform.orgservice.domain.InvitationStatus.PENDING
            """)
    List<Invitation> findAllPendingByEmailNorm(@Param("emailNorm") String emailNorm);

    @Query("""
            select i from Invitation i
             where i.status = com.platform.orgservice.domain.InvitationStatus.PENDING and i.expiresAt <= :now
            """)
    List<Invitation> findExpirable(@Param("now") Instant now);
}
