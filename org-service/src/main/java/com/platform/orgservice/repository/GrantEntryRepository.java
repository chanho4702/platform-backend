package com.platform.orgservice.repository;

import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.domain.SubjectType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface GrantEntryRepository extends JpaRepository<GrantEntry, Long> {

    /**
     * 권한 판정용 — 사용자 직접 grant + 소속 팀 grant 중
     * GLOBAL 또는 (해당 리소스타입 + 리소스 id)에 걸리는 행 전부.
     * teamIds는 비어 있으면 호출부에서 List.of(-1L)로 채운다(빈 IN 절 회피).
     */
    @Query("""
            select g from GrantEntry g
            where ((g.subjectType = com.platform.orgservice.domain.SubjectType.USER and g.subjectId = :userId)
                or (g.subjectType = com.platform.orgservice.domain.SubjectType.TEAM and g.subjectId in :teamIds))
              and (g.resourceType = com.platform.orgservice.domain.ResourceKind.GLOBAL
                or (g.resourceType = :kind and g.resourceId = :resourceId))
            """)
    List<GrantEntry> findEffective(@Param("userId") Long userId,
                                   @Param("teamIds") List<Long> teamIds,
                                   @Param("kind") ResourceKind kind,
                                   @Param("resourceId") String resourceId);

    /** 내 grant 전체 — /me/permissions·ListUserGrants용. (":x is null or" 패턴은 H2 enum 바인딩 리스크가 있어 메서드 분리) */
    @Query("""
            select g from GrantEntry g
            where ((g.subjectType = com.platform.orgservice.domain.SubjectType.USER and g.subjectId = :userId)
                or (g.subjectType = com.platform.orgservice.domain.SubjectType.TEAM and g.subjectId in :teamIds))
            """)
    List<GrantEntry> findAllForUser(@Param("userId") Long userId,
                                    @Param("teamIds") List<Long> teamIds);

    /** 내 grant 중 특정 리소스타입만. */
    @Query("""
            select g from GrantEntry g
            where ((g.subjectType = com.platform.orgservice.domain.SubjectType.USER and g.subjectId = :userId)
                or (g.subjectType = com.platform.orgservice.domain.SubjectType.TEAM and g.subjectId in :teamIds))
              and g.resourceType = :kind
            """)
    List<GrantEntry> findAllForUserByKind(@Param("userId") Long userId,
                                          @Param("teamIds") List<Long> teamIds,
                                          @Param("kind") ResourceKind kind);

    List<GrantEntry> findByResourceTypeAndResourceId(ResourceKind resourceType, String resourceId);

    List<GrantEntry> findBySubjectTypeAndSubjectId(SubjectType subjectType, Long subjectId);

    /**
     * 전역 관리자 수(USER 직접 grant 기준). 팀 경유는 세지 않는다 — 팀에서 사람이 빠지면
     * 관리자가 조용히 0이 될 수 있어 "마지막 한 명" 보호의 근거가 되지 못한다.
     */
    @Query("""
            select count(g) from GrantEntry g
             where g.subjectType = com.platform.orgservice.domain.SubjectType.USER
               and g.resourceType = com.platform.orgservice.domain.ResourceKind.GLOBAL
               and g.role = com.platform.orgservice.domain.GrantRole.ADMIN
            """)
    long countGlobalAdminUsers();

    Optional<GrantEntry> findBySubjectTypeAndSubjectIdAndResourceTypeAndResourceId(
            SubjectType subjectType, Long subjectId, ResourceKind resourceType, String resourceId);
}
