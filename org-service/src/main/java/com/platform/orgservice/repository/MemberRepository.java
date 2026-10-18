package com.platform.orgservice.repository;

import com.platform.orgservice.domain.Member;
import com.platform.orgservice.domain.MemberStatus;
import com.platform.orgservice.domain.MemberKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * 목록 질의는 {@link JpaSpecificationExecutor}로 조립한다 — 선택 조건(status·kind·q)을
 * {@code :x is null or ...}로 쓰면 H2에서 enum 바인딩이 흔들린다(GrantEntryRepository 주석의 같은 이유).
 */
public interface MemberRepository extends JpaRepository<Member, Long>, JpaSpecificationExecutor<Member> {

    java.util.List<Member> findByStatus(MemberStatus status);

    /** 초대 대상이 이미 우리 계정인지 — 이메일은 UNIQUE가 아니라 여러 건일 수 있다. */
    @Query("select m from Member m where lower(m.email) = :emailNorm")
    java.util.List<Member> findByEmailNorm(@Param("emailNorm") String emailNorm);

    /** EVERYONE 팀 백필용 — 활성 사람 멤버 전원. */
    @Query("select m.id from Member m where m.status = :status and m.kind = :kind")
    java.util.List<Long> findIdsByStatusAndKind(@Param("status") MemberStatus status, @Param("kind") MemberKind kind);


    /**
     * 이메일 일치(대소문자 무시). 호출측이 이미 소문자로 눌러서 넘긴다.
     * 비활성 계정을 빼는 이유: 퇴사자에게 이관 문서의 작성자·제한 주체를 붙이면
     * 아무도 손댈 수 없는 문서가 생긴다.
     */
    @Query("select m from Member m where m.status = :status and lower(m.email) in :emails")
    List<Member> findByStatusAndEmailInIgnoreCase(@Param("status") MemberStatus status,
                                                  @Param("emails") Collection<String> emails);

    /**
     * 관리자 대시보드 통계 — 종류별로 상태 분포를 한 번에 센다(GROUP BY 하나).
     * 사람과 에이전트를 나눠 세는 이유는 응답에서 둘을 따로 내기 때문이다(이중 계수 방지).
     */
    @Query("select m.status as status, count(m) as count from Member m where m.kind = :kind group by m.status")
    List<StatusCount> countByStatusForKind(@Param("kind") MemberKind kind);

    /** {@link #countByStatusForKind} 결과 한 줄. */
    interface StatusCount {
        MemberStatus getStatus();
        long getCount();
    }

    long countByKind(MemberKind kind);

    /**
     * auth-server 반영이 밀린 구성원(P-02) — 원하는 값(정지·퇴사면 닫힘)과 반영된 값이 어긋난 행.
     * 네이티브인 이유: V9의 부분 인덱스({@code ix_member_auth_sync_pending})와 술어를 글자 그대로 맞춰야
     * 플래너가 그 인덱스를 쓴다. 상태 목록은 {@link MemberStatus#LOGIN_BLOCKED}와 같아야 한다.
     */
    @Query(value = """
            select * from member
            where (status in ('SUSPENDED', 'DEACTIVATED')) = auth_synced_enabled
            order by id
            limit :limit
            """, nativeQuery = true)
    List<Member> findAuthSyncPending(@Param("limit") int limit);

    /**
     * auth-server 반영 확인 기록. 보낸 뒤 상태가 또 바뀌었으면(조건 불일치) 0행 — 다음 주기에 다시 보낸다.
     * 네이티브인 이유: 이 열은 엔티티에서 읽기 전용(insertable·updatable=false)이다.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query(value = "update member set auth_synced_enabled = :enabled where id = :id and status = :status",
           nativeQuery = true)
    int markAuthSynced(@Param("id") long id, @Param("status") String status, @Param("enabled") boolean enabled);

    /**
     * 이메일 local-part 일치(대소문자 무시) — org에는 username 컬럼이 없어 이것이 username 규칙이다.
     * {@code locate('@', email) > 1}로 '@'가 없거나 맨 앞인 값을 먼저 걸러 낸다(substring 인자가 음수가 된다).
     */
    @Query("""
            select m from Member m
            where m.status = :status
              and m.email is not null
              and locate('@', m.email) > 1
              and lower(substring(m.email, 1, locate('@', m.email) - 1)) in :localParts
            """)
    List<Member> findByStatusAndEmailLocalPartInIgnoreCase(@Param("status") MemberStatus status,
                                                           @Param("localParts") Collection<String> localParts);
}
