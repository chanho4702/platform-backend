package com.platform.orgservice.authsync;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 구성원 상태 → auth-server 동기화 주기(P-02). 일은 {@link AuthAccountSyncService}가 한다.
 * 정지·퇴사 처리 후 최대 이 주기(기본 5초) 안에 그 사람의 리프레시 토큰이 폐기되고 PAT 교환이 막힌다.
 */
@Component
@ConditionalOnProperty(prefix = "platform.org.auth-sync", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class AuthAccountSyncWorker {

    private final AuthAccountSyncService sync;

    @Scheduled(fixedDelayString = "${platform.org.auth-sync.poll-ms:5000}",
               initialDelayString = "${platform.org.auth-sync.poll-ms:5000}")
    public void poll() {
        try {
            int n = sync.syncOnce();
            if (n > 0) log.info("auth-server 계정 상태 {}건 반영", n);
        } catch (Exception e) {
            // 스케줄러는 예외가 나면 그 잡을 멈춘다 — 삼키지 않으면 DB 한 번 끊겼다고 전파가 영영 선다.
            log.warn("auth-server 계정 상태 동기화 실패: {}", e.getClass().getSimpleName(), e);
        }
    }
}
