package com.platform.orgservice.mail;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 큐를 비우는 배치.
 *
 * <p>여기에는 일정만 있고 일은 {@link MailOutboxService}가 한다 — {@code @Scheduled} 메서드가 같은 빈의
 * {@code @Transactional} 메서드를 부르면 프록시를 지나가지 않아 트랜잭션 없이 돈다(행 잠금이 사라진다).
 *
 * <p>인스턴스가 하나라는 가정이지만 잠금은 {@code FOR UPDATE SKIP LOCKED}로 건다 —
 * 배포 중 잠깐 둘이 겹쳐도 같은 메일이 두 번 나가지 않는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MailOutboxWorker {

    private final MailOutboxService outbox;

    /**
     * 5초마다. {@code initialDelay}를 같은 값으로 두는 이유는 기동 직후 DB·설정이 준비되기 전에
     * 첫 폴링이 들어오지 않게 하기 위해서다.
     */
    @Scheduled(fixedDelayString = "${platform.org.mail.outbox.poll-ms:5000}",
               initialDelayString = "${platform.org.mail.outbox.poll-ms:5000}")
    public void poll() {
        try {
            int sent = outbox.drainOnce();
            if (sent > 0) log.info("메일 발송 {}건", sent);
        } catch (Exception e) {
            // 스케줄러는 예외가 나면 그 잡을 멈춘다 — 여기서 삼키지 않으면 DB 한 번 끊겼다고 큐가 영영 선다.
            log.warn("메일 큐 처리 실패: {}", e.getClass().getSimpleName(), e);
        }
    }

    /** 30일 지난 종결분 정리 — 일 1회. */
    @Scheduled(cron = "${platform.org.mail.outbox.cleanup-cron:0 40 4 * * *}")
    public void cleanup() {
        try {
            outbox.cleanup();
        } catch (Exception e) {
            log.warn("메일 로그 정리 실패: {}", e.getClass().getSimpleName(), e);
        }
    }
}
