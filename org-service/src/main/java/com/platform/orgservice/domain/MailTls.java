package com.platform.orgservice.domain;

/**
 * SMTP 전송 보안. 세 가지뿐인 이유는 실제로 만나는 서버가 이 셋으로 갈리기 때문이다 —
 * 사내 릴레이(평문 25), 대부분의 공용 SMTP(587 STARTTLS), 레거시 암시적 TLS(465).
 */
public enum MailTls {
    /** 평문. 사내망 릴레이(mail-relay:25)처럼 네트워크 경계가 대신 지키는 경우에만 쓴다. */
    NONE,
    /** 평문으로 열고 STARTTLS로 승격. 587의 기본값이다. */
    STARTTLS,
    /** 처음부터 TLS(암시적, 보통 465). */
    SSL
}
