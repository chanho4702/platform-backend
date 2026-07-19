package com.platform.orgservice.permission;

import com.platform.orgservice.domain.*;
import com.platform.orgservice.repository.GrantEntryRepository;
import com.platform.orgservice.repository.MemberRepository;
import com.platform.orgservice.repository.TeamMemberRepository;
import com.platform.orgservice.repository.TeamRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PermissionFacadeTest {

    @Autowired PermissionFacade facade;
    @Autowired GrantEntryRepository grants;
    @Autowired TeamRepository teams;
    @Autowired TeamMemberRepository teamMembers;
    @Autowired MemberRepository members;

    @Test
    void 직접grant와_팀grant_중_최고role로_판정한다() {
        members.save(Member.of(1L, "Alice", null));
        Team dev = teams.save(Team.of("dev", null));
        teamMembers.save(TeamMember.of(dev.getId(), 1L, TeamRole.MEMBER));
        grants.save(GrantEntry.of(SubjectType.USER, 1L, ResourceKind.SPACE, "sp-1", GrantRole.VIEWER));
        grants.save(GrantEntry.of(SubjectType.TEAM, dev.getId(), ResourceKind.SPACE, "sp-1", GrantRole.EDITOR));

        PermissionFacade.Decision d = facade.check(1L, ResourceKind.SPACE, "sp-1", PermAction.EDIT);

        assertThat(d.allowed()).isTrue();
        assertThat(d.effectiveRole()).isEqualTo(GrantRole.EDITOR);
    }

    @Test
    void 상위action은_하위role로_거부된다() {
        grants.save(GrantEntry.of(SubjectType.USER, 2L, ResourceKind.SPACE, "sp-1", GrantRole.VIEWER));

        assertThat(facade.check(2L, ResourceKind.SPACE, "sp-1", PermAction.VIEW).allowed()).isTrue();
        assertThat(facade.check(2L, ResourceKind.SPACE, "sp-1", PermAction.EDIT).allowed()).isFalse();
    }

    @Test
    void GLOBAL_grant는_모든_리소스에_적용된다() {
        grants.save(GrantEntry.of(SubjectType.USER, 3L, ResourceKind.GLOBAL, "", GrantRole.ADMIN));

        assertThat(facade.check(3L, ResourceKind.SPACE, "any", PermAction.ADMIN).allowed()).isTrue();
        assertThat(facade.check(3L, ResourceKind.PROJECT, "any", PermAction.EDIT).allowed()).isTrue();
    }

    @Test
    void grant가_없으면_거부되고_effectiveRole은_null이다() {
        PermissionFacade.Decision d = facade.check(99L, ResourceKind.SPACE, "sp-1", PermAction.VIEW);

        assertThat(d.allowed()).isFalse();
        assertThat(d.effectiveRole()).isNull();
    }

    @Test
    void requireGlobalAdmin은_비관리자에게_AccessDeniedException을_던진다() {
        grants.save(GrantEntry.of(SubjectType.USER, 4L, ResourceKind.GLOBAL, "", GrantRole.ADMIN));

        facade.requireGlobalAdmin(4L); // 통과
        assertThatThrownBy(() -> facade.requireGlobalAdmin(5L))
                .isInstanceOf(AccessDeniedException.class);
    }
}
