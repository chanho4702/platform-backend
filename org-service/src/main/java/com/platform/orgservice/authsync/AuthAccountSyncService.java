package com.platform.orgservice.authsync;

import com.platform.orgservice.domain.Member;
import com.platform.orgservice.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 구성원 상태 → auth-server 계정 상태 맞추기(P-02).
 *
 * <p><b>왜 이벤트(Redis Streams)가 아니라 내부 API + 상태 대조인가.</b> org-service·auth-server 둘 다 Redis를
 * 쓰지 않는다 — 이벤트를 고르면 두 서비스에 Redis 의존·소비자 그룹·DLQ가 새로 생긴다. 반면 auth-server는 이미
 * org의 내부 API를 공유 토큰으로 부르고 있다(초대 확인·수락). 같은 토큰으로 방향만 반대인 호출 하나면 된다.
 * 그리고 "이벤트를 보냈다"가 아니라 "원하는 값(status)과 반영된 값(auth_synced_enabled)이 같은가"를 주기적으로
 * 대조하므로, auth-server가 죽어 있던 동안의 변경·상태를 바꾸는 새 경로가 생겨도 유실되지 않는다.
 *
 * <p>한 주기는 어긋난 행을 최대 {@code batch}개 보낸다. 보낸 뒤 반영 기록은 조건부 UPDATE(그 사이 상태가 또 바뀌었으면
 * 0행 → 다음 주기에 새 값으로 다시 보낸다).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthAccountSyncService {

    private final MemberRepository members;
    private final AuthAccountClient client;
    private final AtomicBoolean warnedUnconfigured = new AtomicBoolean();

    @Value("${platform.org.auth-sync.batch-size:50}")
    private int batchSize = 50;

    /** @return 이번 주기에 반영이 확인된 건수 */
    public int syncOnce() {
        List<Member> pending = members.findAuthSyncPending(Math.max(1, batchSize));
        if (pending.isEmpty()) return 0;
        if (!client.isConfigured()) {
            if (warnedUnconfigured.compareAndSet(false, true)) {
                log.error("구성원 비활성을 auth-server에 전파할 수 없다 — ORG_INTERNAL_TOKEN 또는 auth-server 주소가 비어 있다. "
                        + "정지·퇴사자의 리프레시 토큰·PAT가 살아 있다: 대기 {}건", pending.size());
            }
            return 0;
        }
        int synced = 0;
        for (Member m : pending) {
            boolean active = m.getStatus().allowsLogin();
            AuthAccountClient.Result result = client.setActive(m.getId(), active);
            switch (result) {
                case APPLIED -> {
                    synced += members.markAuthSynced(m.getId(), m.getStatus().name(), active);
                    log.info("auth-server 계정 상태 반영: member={} status={} active={}", m.getId(), m.getStatus(), active);
                }
                case USER_NOT_FOUND -> {
                    // auth-server에 없는 사용자(테스트 시드·정리된 계정) — 닫을 세션도 없다. 반복 시도하지 않게 기록만.
                    synced += members.markAuthSynced(m.getId(), m.getStatus().name(), active);
                    log.warn("auth-server에 없는 사용자 — 반영할 것 없음: member={}", m.getId());
                }
                case FAILED, NOT_CONFIGURED -> {
                    // 다음 주기에 다시. 한 건이 실패하면 나머지도 같은 원인(auth 불능)일 가능성이 높아 이번 주기는 멈춘다.
                    return synced;
                }
            }
        }
        return synced;
    }
}
