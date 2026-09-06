package com.platform.orgservice.mail;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 기동 시 메일 설정 행이 있는지 확인하고, 없으면 {@code MAIL_SEED_*}로 만든다.
 *
 * <p>마이그레이션이 아니라 여기서 심는 이유는 값이 env에서 오기 때문이다(SQL은 env를 못 읽는다).
 * 이미 행이 있으면 손대지 않는다 — 운영 중 정본은 관리 화면이 고친 DB 값이다.
 */
@Component
@RequiredArgsConstructor
@Order(110) // 다른 시더(관리자·전체 구성원 팀)와 순서 다툼 없이 뒤에 둔다
public class MailSettingSeeder implements ApplicationRunner {

    private final MailSettingService settings;

    @Override
    public void run(ApplicationArguments args) {
        settings.ensureSeeded();
    }
}
