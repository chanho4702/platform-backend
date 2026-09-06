package com.platform.orgservice.admin;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.platform.orgservice.admin.dto.OrgStatsResponse;
import com.platform.orgservice.domain.InvitationStatus;
import com.platform.orgservice.domain.MemberKind;
import com.platform.orgservice.domain.MemberStatus;
import com.platform.orgservice.permission.PermissionFacade;
import com.platform.orgservice.repository.InvitationRepository;
import com.platform.orgservice.repository.MemberRepository;
import com.platform.orgservice.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 관리자 대시보드가 읽는 조직 현황. 질의는 COUNT뿐이다 — GROUP BY 하나(멤버 상태별)와 COUNT 셋.
 *
 * <p>60초 캐시를 두는 이유는 부하 원칙(스펙 §0)이다. 대시보드를 몇 명이 보든 이 서비스가 받는 질의는
 * 분당 한 묶음을 넘지 않는다. 대가는 최대 60초의 지연이며, 관리자가 눈으로 보는 총량 지표에는
 * 그 정도 신선도면 충분하다. 권한 판정은 <b>캐시 밖</b>에 있다 — 캐시가 인가를 건너뛰게 두지 않는다.
 */
@Service
@RequiredArgsConstructor
public class OrgStatsService {

    /** 전역 통계라 사용자별로 갈리지 않는다 — 항목이 하나뿐인 캐시. */
    private static final String KEY = "org-stats";

    /**
     * 응답에 담는 순서. enum 선언 순서(PENDING이 먼저)가 아니라 화면이 읽는 순서다 —
     * 카드가 {@code Object.entries()}로 그대로 늘어놓아도 활성부터 보이게 한다.
     */
    private static final List<MemberStatus> DISPLAY_ORDER = List.of(
            MemberStatus.ACTIVE, MemberStatus.PENDING, MemberStatus.SUSPENDED, MemberStatus.DEACTIVATED);

    private static final Duration TTL = Duration.ofSeconds(60);

    private final PermissionFacade permissions;
    private final MemberRepository members;
    private final TeamRepository teams;
    private final InvitationRepository invitations;

    private final Cache<String, OrgStatsResponse> cache = Caffeine.newBuilder()
            .expireAfterWrite(TTL)
            .maximumSize(1)
            .build();

    public OrgStatsResponse stats(long actorId) {
        permissions.requireGlobalAdmin(actorId);
        return cache.get(KEY, key -> compute());
    }

    /** 테스트 격리용. 운영에서는 TTL로만 비운다. */
    public void evictAll() {
        cache.invalidateAll();
    }

    /**
     * 트랜잭션을 걸지 않는다 — 여기는 캐시 로더가 부르는 자기 호출이라 프록시가 끼지 않고,
     * 걸어 봐야 동작하지 않는 애너테이션만 남는다. 질의 넷은 각자 커넥션을 잡고 끝난다.
     */
    private OrgStatsResponse compute() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (MemberStatus status : DISPLAY_ORDER) {
            byStatus.put(status.name(), 0L);
        }
        for (MemberRepository.StatusCount row : members.countByStatusForKind(MemberKind.HUMAN)) {
            byStatus.put(row.getStatus().name(), row.getCount());
        }
        return new OrgStatsResponse(
                byStatus,
                members.countByKind(MemberKind.AGENT),
                teams.count(),
                invitations.countByStatus(InvitationStatus.PENDING));
    }
}
