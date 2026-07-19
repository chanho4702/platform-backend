package com.platform.orgservice.config;

import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.GrantRole;
import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.domain.SubjectType;
import com.platform.orgservice.repository.GrantEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "platform.bootstrap-admin-id=42")
@ActiveProfiles("test")
class BootstrapAdminSeederTest {

    @Autowired GrantEntryRepository grants;
    @Autowired BootstrapAdminSeeder seeder;

    @Test
    void 기동_시_지정_계정이_GLOBAL_ADMIN으로_시드된다() {
        assertThat(grants.findBySubjectTypeAndSubjectIdAndResourceTypeAndResourceId(
                SubjectType.USER, 42L, ResourceKind.GLOBAL, ""))
                .isPresent()
                .get().satisfies(g -> assertThat(g.getRole()).isEqualTo(GrantRole.ADMIN));
    }

    @Test
    void 기존_grant가_강등돼_있어도_재기동_시_ADMIN으로_복구된다() {
        var g = grants.findBySubjectTypeAndSubjectIdAndResourceTypeAndResourceId(
                SubjectType.USER, 42L, ResourceKind.GLOBAL, "").orElseThrow();
        g.changeRole(GrantRole.VIEWER);
        grants.saveAndFlush(g);

        seeder.run(null); // 재기동 시뮬레이션

        assertThat(grants.findBySubjectTypeAndSubjectIdAndResourceTypeAndResourceId(
                SubjectType.USER, 42L, ResourceKind.GLOBAL, "").orElseThrow().getRole())
                .isEqualTo(GrantRole.ADMIN);
    }
}
