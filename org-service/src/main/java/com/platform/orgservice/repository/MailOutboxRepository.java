package com.platform.orgservice.repository;

import com.platform.orgservice.domain.MailOutbox;
import com.platform.orgservice.domain.MailStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface MailOutboxRepository extends JpaRepository<MailOutbox, Long> {

    /**
     * 워커가 집을 차례가 된 행. {@code FOR UPDATE SKIP LOCKED}로 잠근다 —
     * 인스턴스가 둘로 늘어도 같은 메일을 두 번 보내지 않고, 남이 잡은 행에서 기다리지도 않는다.
     *
     * <p>잠금 힌트는 {@code jakarta.persistence.lock.timeout = -2}(SKIP_LOCKED)다. PostgreSQL에서는
     * {@code for update skip locked}로 나가고, 그 문법이 없는 H2(테스트)에서는 Hibernate가 평범한
     * {@code for update}로 내린다 — 방언 차이를 여기서 분기하지 않으려고 네이티브 SQL을 쓰지 않았다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select m from MailOutbox m
             where m.status = com.platform.orgservice.domain.MailStatus.PENDING
               and m.nextAttemptAt <= :now
             order by m.nextAttemptAt asc, m.id asc
            """)
    List<MailOutbox> claimDue(@Param("now") Instant now, Pageable pageable);

    Page<MailOutbox> findByStatusOrderByCreatedAtDescIdDesc(MailStatus status, Pageable pageable);

    Page<MailOutbox> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    /** 30일 지난 종결분 정리. PENDING은 지우지 않는다 — 아직 배달할 것이 남아 있다는 뜻이다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from MailOutbox m
             where m.status <> com.platform.orgservice.domain.MailStatus.PENDING
               and m.createdAt < :before
            """)
    int deleteSettledBefore(@Param("before") Instant before);

    long countByStatus(MailStatus status);
}
