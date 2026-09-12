package com.platform.orgservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code PLATFORM_BOOTSTRAP_ADMIN_ID} — 첫 설치의 최초 관리자(auth-server user id).
 *
 * <p>두 곳이 같은 값을 봐야 해서 파싱을 한 곳에 모았다: 기동 시드({@link BootstrapAdminSeeder})가
 * GLOBAL ADMIN grant를 맞추고, 첫 로그인 미러링
 * ({@code MemberService#mirror})이 이 사람만 승인 대기를 건너뛰게 한다. 값이 갈라지면
 * "관리자 권한은 있는데 PENDING으로 갇힌 계정"이 다시 생긴다.
 *
 * <p>숫자가 아니면 기동을 실패시킨다 — 오타 난 설치가 "관리자 없는 플랫폼"으로 조용히 떠 있는 쪽이 더 나쁘다.
 */
@Component
public class BootstrapAdminId {

    private final Long id;

    public BootstrapAdminId(@Value("${platform.bootstrap-admin-id:}") String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            this.id = null;
            return;
        }
        try {
            this.id = Long.valueOf(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "platform.bootstrap-admin-id는 auth user id(숫자)여야 합니다: " + raw, e);
        }
    }

    /** 설정되지 않았으면 {@code null} — 이때는 부트스트랩 동작 전체를 건너뛴다. */
    public Long value() {
        return id;
    }

    public boolean matches(long userId) {
        return id != null && id == userId;
    }
}
