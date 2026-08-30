package com.platform.orgservice.repository;

import com.platform.orgservice.domain.GrantAudit;
import com.platform.orgservice.domain.ResourceKind;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface GrantAuditRepository extends JpaRepository<GrantAudit, Long> {

    /** 최신이 먼저. id를 2차 기준으로 두어 같은 시각의 기록도 순서가 흔들리지 않는다. */
    @Query("""
            select a from GrantAudit a
             where a.resourceType = :resourceType and a.resourceId = :resourceId
             order by a.createdAt desc, a.id desc
            """)
    List<GrantAudit> findByResource(@Param("resourceType") ResourceKind resourceType,
                                    @Param("resourceId") String resourceId,
                                    Limit limit);
}
