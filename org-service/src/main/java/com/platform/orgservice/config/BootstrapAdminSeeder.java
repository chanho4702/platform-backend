package com.platform.orgservice.config;

import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.GrantRole;
import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.domain.SubjectType;
import com.platform.orgservice.repository.GrantEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 최초 관리자 부트스트랩 — PLATFORM_BOOTSTRAP_ADMIN_ID(auth user id)를
 * 기동마다 GLOBAL ADMIN grant로 upsert. 미설정이면 아무것도 안 함.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BootstrapAdminSeeder implements ApplicationRunner {

    private final GrantEntryRepository grants;

    @Value("${platform.bootstrap-admin-id:}")
    private String bootstrapAdminId;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (bootstrapAdminId == null || bootstrapAdminId.isBlank()) return;
        long userId = Long.parseLong(bootstrapAdminId.trim());
        grants.findBySubjectTypeAndSubjectIdAndResourceTypeAndResourceId(
                        SubjectType.USER, userId, ResourceKind.GLOBAL, "")
                .ifPresentOrElse(
                        g -> g.changeRole(GrantRole.ADMIN), // 강등돼 있어도 기동 시 복구
                        () -> grants.save(GrantEntry.globalAdmin(userId)));
        log.info("부트스트랩 GLOBAL ADMIN 시드: userId={}", userId);
    }
}
