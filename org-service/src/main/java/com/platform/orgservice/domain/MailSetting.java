package com.platform.orgservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 플랫폼 메일 설정 — 언제나 한 행({@code id=1})이다.
 *
 * <p>설치 옵션({@code MAIL_MODE})은 <b>어떤 컨테이너를 띄우고 무엇을 초기값으로 심을지</b>를 정하고,
 * 운영 중 정본은 이 행이다. env를 정본으로 두면 값을 바꾸려 재배포해야 하고, 관리 화면에서 고친 값이
 * 다음 기동에 조용히 되돌아간다.
 *
 * <p>비밀번호는 {@code passwordEnc}(AES-GCM 암호문)로만 산다. 평문 컬럼을 두지 않는 이유는 DB 덤프가
 * 그대로 SMTP 계정이 되기 때문이고, 응답에도 절대 싣지 않는다({@code passwordSet} 불리언만 나간다).
 */
@Entity
@Table(name = "mail_setting")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MailSetting {

    /** 이 테이블의 유일한 키. 상수로 두어 "어느 행이냐"는 질문이 코드에 생기지 않게 한다. */
    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(nullable = false)
    private boolean enabled;

    @Column(length = 255)
    private String host;

    @Column(nullable = false)
    private int port;

    @Column(length = 320)
    private String username;

    @Column(name = "password_enc", columnDefinition = "text")
    private String passwordEnc;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MailTls tls;

    @Column(name = "from_address", length = 320)
    private String fromAddress;

    @Column(name = "from_name", length = 120)
    private String fromName;

    @Column(name = "updated_by")
    private Long updatedBy;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 시드 전용 — 최초 1회, 행이 없을 때만. {@code updatedBy}는 사람이 아니므로 null이다. */
    public static MailSetting seed(boolean enabled, String host, int port, String username,
                                   String passwordEnc, MailTls tls, String fromAddress, String fromName) {
        MailSetting s = new MailSetting();
        s.id = SINGLETON_ID;
        s.enabled = enabled;
        s.host = blankToNull(host);
        s.port = port;
        s.username = blankToNull(username);
        s.passwordEnc = blankToNull(passwordEnc);
        s.tls = tls == null ? MailTls.STARTTLS : tls;
        s.fromAddress = blankToNull(fromAddress);
        s.fromName = blankToNull(fromName);
        return s;
    }

    /**
     * 관리 화면의 저장. 비밀번호는 세 갈래다 — 그대로 두기(null), 지우기(빈 문자열), 새 값(암호문).
     * 그 판정은 서비스가 하고 여기에는 결과만 온다.
     */
    public void update(boolean enabled, String host, int port, String username,
                       MailTls tls, String fromAddress, String fromName, long updatedBy) {
        this.enabled = enabled;
        this.host = blankToNull(host);
        this.port = port;
        this.username = blankToNull(username);
        this.tls = tls == null ? MailTls.STARTTLS : tls;
        this.fromAddress = blankToNull(fromAddress);
        this.fromName = blankToNull(fromName);
        this.updatedBy = updatedBy;
    }

    public void changePassword(String passwordEnc) { this.passwordEnc = blankToNull(passwordEnc); }

    public boolean hasPassword() { return passwordEnc != null && !passwordEnc.isBlank(); }

    /**
     * 보낼 수 있는 상태인가. {@code enabled}만으로는 부족하다 — 호스트나 보내는 주소가 비면
     * SMTP 단계에서야 실패해 로그에 원인이 흐릿하게 남는다.
     */
    public boolean sendable() {
        return enabled && host != null && !host.isBlank()
                && fromAddress != null && !fromAddress.isBlank();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
