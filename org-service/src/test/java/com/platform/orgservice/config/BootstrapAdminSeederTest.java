package com.platform.orgservice.config;

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

    @Test
    void 기동_시_지정_계정이_GLOBAL_ADMIN으로_시드된다() {
        assertThat(grants.findBySubjectTypeAndSubjectIdAndResourceTypeAndResourceId(
                SubjectType.USER, 42L, ResourceKind.GLOBAL, ""))
                .isPresent()
                .get().satisfies(g -> assertThat(g.getRole()).isEqualTo(GrantRole.ADMIN));
    }
}
