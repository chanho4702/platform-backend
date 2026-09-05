package com.platform.orgservice.invitation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 만료된 초대를 눕힌다.
 *
 * <p>수락 경로에서도 만료를 확인하지만(그때 확인하지 않으면 지난 링크로 들어올 수 있다), 배치가 따로 있는
 * 이유는 목록 화면 때문이다 — 아무도 쓰지 않은 초대는 영영 PENDING으로 남아 "대기 중"으로 보인다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InvitationExpiryScheduler {

    private final InvitationService invitations;

    @Scheduled(cron = "${platform.org.invitation.expiry-cron:0 */10 * * * *}")
    public void expireDue() {
        int expired = invitations.expireDue(Instant.now());
        if (expired > 0) log.info("만료 처리된 초대 {}건", expired);
    }
}
