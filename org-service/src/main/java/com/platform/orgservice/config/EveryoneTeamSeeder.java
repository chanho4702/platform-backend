package com.platform.orgservice.config;

import com.platform.orgservice.domain.MemberKind;
import com.platform.orgservice.domain.MemberStatus;
import com.platform.orgservice.repository.MemberRepository;
import com.platform.orgservice.team.EveryoneTeamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * "전체 구성원" 팀이 항상 존재하고, 활성 사람 멤버가 전원 들어 있게 맞춘다.
 *
 * <p>Flyway V6가 시드하지만 기동 시 한 번 더 맞추는 이유는 두 가지다: 마이그레이션이 돌지 않는
 * 환경(H2 테스트)에서도 이 팀이 있어야 하고, "활성 사람 = 전체 구성원"은 불변식이라 어긋난 채로
 * 오래 굴러가면 공개 스페이스가 조용히 일부에게만 보이게 된다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Order(100) // BootstrapAdminSeeder가 만든 grant와 순서 다툼이 없도록 뒤에 둔다
public class EveryoneTeamSeeder implements ApplicationRunner {

    private final EveryoneTeamService everyone;
    private final MemberRepository members;

    @Override
    public void run(ApplicationArguments args) {
        everyone.ensure();
        List<Long> active = members.findIdsByStatusAndKind(MemberStatus.ACTIVE, MemberKind.HUMAN);
        active.forEach(everyone::add);
        log.info("전체 구성원 팀 정합 완료: 활성 사람 멤버 {}명", active.size());
    }
}
