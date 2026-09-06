package com.platform.orgservice.domain;

/** 발송 큐의 상태. FAILED는 자동 재시도를 다 쓴 상태이고, 관리자가 로그에서 다시 밀어 넣을 수 있다. */
public enum MailStatus {
    PENDING, SENT, FAILED
}
