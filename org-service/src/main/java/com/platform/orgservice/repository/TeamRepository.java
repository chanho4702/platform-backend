package com.platform.orgservice.repository;

import com.platform.orgservice.domain.Team;
import com.platform.orgservice.domain.TeamKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface TeamRepository extends JpaRepository<Team, Long> {
    boolean existsByName(String name);

    /** "전체 구성원" 팀 — 하나뿐이라는 전제는 서비스가 지킨다(생성 경로가 시더 하나). */
    java.util.Optional<Team> findFirstByKind(TeamKind kind);

    java.util.List<Team> findByNameContainingIgnoreCase(String q);

    /** 팀 이름 일치(대소문자 무시). team.name의 UNIQUE는 대소문자를 구분해 결과가 둘일 수 있다. */
    @Query("select t from Team t where lower(t.name) in :names")
    List<Team> findByNameInIgnoreCase(@Param("names") Collection<String> names);
}
