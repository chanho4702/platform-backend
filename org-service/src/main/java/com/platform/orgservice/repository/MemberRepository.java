package com.platform.orgservice.repository;

import com.platform.orgservice.domain.Member;
import com.platform.orgservice.domain.MemberStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface MemberRepository extends JpaRepository<Member, Long> {

    /**
     * 이메일 일치(대소문자 무시). 호출측이 이미 소문자로 눌러서 넘긴다.
     * 비활성 계정을 빼는 이유: 퇴사자에게 이관 문서의 작성자·제한 주체를 붙이면
     * 아무도 손댈 수 없는 문서가 생긴다.
     */
    @Query("select m from Member m where m.status = :status and lower(m.email) in :emails")
    List<Member> findByStatusAndEmailInIgnoreCase(@Param("status") MemberStatus status,
                                                  @Param("emails") Collection<String> emails);

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
