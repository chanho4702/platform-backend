package com.platform.orgservice.domain;

/**
 * 초대가 어떻게 소진됐는가.
 *
 * <p>TOKEN은 초대 링크를 타고 온 경우(auth-server가 로그인 성공 후 알려준다), EMAIL_MATCH는
 * 링크 없이 그냥 로그인했는데 이메일이 초대와 일치한 경우다. 후자는 이메일을 신뢰할 수 있는
 * 로그인 경로(구글 IdP)에서만 인정한다 — Keycloak 이메일 검증이 꺼져 있는 동안 비밀번호 가입자가
 * 남의 이메일을 적어 남의 초대를 가로챌 수 있기 때문이다.
 */
public enum AcceptedVia { TOKEN, EMAIL_MATCH }
