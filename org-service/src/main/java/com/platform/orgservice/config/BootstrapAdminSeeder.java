package com.platform.orgservice.config;

import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.GrantRole;
import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.domain.SubjectType;
import com.platform.orgservice.member.MemberService;
import com.platform.orgservice.repository.GrantEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 최초 관리자 부트스트랩 — {@code PLATFORM_BOOTSTRAP_ADMIN_ID}(auth user id)를 기동마다
 * GLOBAL ADMIN grant로 upsert하고, 그 계정이 승인 대기로 갇혀 있으면 활성으로 올린다.
 * 미설정이면 아무것도 안 한다.
 *
 * <p>승격을 같이 하는 이유: grant만 주면 첫 로그인이 {@code MemberMirrorFilter}에서 PENDING으로
 * 격리되고, 승인 API는 "활성 전역 관리자"를 요구하므로 그 계정을 풀어 줄 사람이 아무도 없다.
 * 설치가 거기서 막혀 수동 SQL이 필요했다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BootstrapAdminSeeder implements ApplicationRunner {

    private final GrantEntryRepository grants;
    private final MemberService members;
    private final BootstrapAdminId bootstrapAdmin;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Long userId = bootstrapAdmin.value();
        if (userId == null) return;
        grants.findBySubjectTypeAndSubjectIdAndResourceTypeAndResourceId(
                        SubjectType.USER, userId, ResourceKind.GLOBAL, "")
                .ifPresentOrElse(
                        g -> g.changeRole(GrantRole.ADMIN), // 강등돼 있어도 기동 시 복구
                        () -> grants.save(GrantEntry.globalAdmin(userId)));
        // 아직 로그인하지 않았으면 멤버 행이 없다 — 그때는 첫 로그인 미러링이 활성으로 만든다.
        members.promoteBootstrapAdmin(userId);
        log.info("부트스트랩 GLOBAL ADMIN 시드: userId={}", userId);
    }
}
