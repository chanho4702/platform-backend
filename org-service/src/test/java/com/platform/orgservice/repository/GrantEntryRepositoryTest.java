package com.platform.orgservice.repository;

import com.platform.orgservice.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class GrantEntryRepositoryTest {

    @Autowired GrantEntryRepository grants;
    @Autowired TeamMemberRepository teamMembers;
    @Autowired TeamRepository teams;
    @Autowired MemberRepository members;

    @Test
    void findEffective_직접grant와_팀grant와_GLOBAL을_모두_수집한다() {
        members.save(Member.of(1L, "Alice", "a@x.com"));
        Team dev = teams.save(Team.of("dev", null));
        teamMembers.save(TeamMember.of(dev.getId(), 1L, TeamRole.MEMBER));

        grants.save(GrantEntry.of(SubjectType.USER, 1L, ResourceKind.SPACE, "sp-1", GrantRole.VIEWER)); // 직접
        grants.save(GrantEntry.of(SubjectType.TEAM, dev.getId(), ResourceKind.SPACE, "sp-1", GrantRole.EDITOR)); // 팀 경유
        grants.save(GrantEntry.of(SubjectType.USER, 1L, ResourceKind.GLOBAL, "", GrantRole.VIEWER)); // GLOBAL
        grants.save(GrantEntry.of(SubjectType.USER, 1L, ResourceKind.SPACE, "sp-2", GrantRole.ADMIN)); // 다른 리소스 — 제외 대상

        List<GrantEntry> found = grants.findEffective(1L, List.of(dev.getId()), ResourceKind.SPACE, "sp-1");

        assertThat(found).hasSize(3)
                .extracting(GrantEntry::getRole)
                .containsExactlyInAnyOrder(GrantRole.VIEWER, GrantRole.EDITOR, GrantRole.VIEWER);
    }

    @Test
    void findAllForUser_전체와_리소스타입_필터를_각각_반환한다() {
        grants.save(GrantEntry.of(SubjectType.USER, 2L, ResourceKind.GLOBAL, "", GrantRole.ADMIN));
        grants.save(GrantEntry.of(SubjectType.USER, 2L, ResourceKind.PROJECT, "pj-1", GrantRole.EDITOR));

        assertThat(grants.findAllForUser(2L, List.of(-1L))).hasSize(2);
        assertThat(grants.findAllForUserByKind(2L, List.of(-1L), ResourceKind.PROJECT)).hasSize(1);
    }
}
