package com.platform.orgservice.mail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SMTP 비밀번호 암복호화. 여기서 막는 사고는 셋이다 — 평문이 그대로 저장되는 것,
 * 같은 값을 두 번 저장했을 때 같은 암호문이 나오는 것(IV 재사용), 키가 바뀐 줄 모르고 조용히 실패하는 것.
 */
class MailCryptoTest {

    private static final String KEY_A = "0".repeat(63) + "1";  // 32바이트 hex
    private static final String KEY_B = "f".repeat(64);

    @Test
    void 암호화한_값을_같은_키로_되돌린다() {
        MailCrypto crypto = new MailCrypto(KEY_A);

        String encrypted = crypto.encrypt("s3cr3t-비밀번호");

        assertThat(crypto.isConfigured()).isTrue();
        assertThat(encrypted).doesNotContain("s3cr3t");
        assertThat(crypto.decrypt(encrypted)).isEqualTo("s3cr3t-비밀번호");
    }

    /** GCM은 같은 키로 IV를 재사용하면 평문이 드러난다 — 저장할 때마다 새로 뽑는지 확인한다. */
    @Test
    void 같은_값을_두_번_암호화하면_다른_암호문이_나온다() {
        MailCrypto crypto = new MailCrypto(KEY_A);

        assertThat(crypto.encrypt("같은값")).isNotEqualTo(crypto.encrypt("같은값"));
    }

    @Test
    void 키가_바뀌면_복호화에_실패하고_이유를_말한다() {
        String encrypted = new MailCrypto(KEY_A).encrypt("비밀번호");

        assertThatThrownBy(() -> new MailCrypto(KEY_B).decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ORG_SETTINGS_ENC_KEY가 바뀌었습니다");
    }

    @Test
    void 키가_없으면_미설정이고_암호화를_거부한다() {
        MailCrypto crypto = new MailCrypto("");

        assertThat(crypto.isConfigured()).isFalse();
        assertThatThrownBy(() -> crypto.encrypt("비밀번호")).isInstanceOf(IllegalStateException.class);
    }

    /** 잘못된 키로 뜨면 저장된 비밀번호를 못 읽는 채로 "정상"처럼 보인다 — 기동을 세운다. */
    @Test
    void 길이가_틀린_키는_기동을_세운다() {
        assertThatThrownBy(() -> new MailCrypto("abcd"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32바이트 hex");
        assertThatThrownBy(() -> new MailCrypto("hex가 아님"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32바이트 hex");
    }
}
