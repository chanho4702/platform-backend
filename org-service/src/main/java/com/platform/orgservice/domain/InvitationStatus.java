package com.platform.orgservice.domain;

/** PENDING만 살아 있는 초대다. 나머지 셋은 끝난 상태이며 되돌리지 않는다(다시 보내면 새 초대). */
public enum InvitationStatus { PENDING, ACCEPTED, EXPIRED, REVOKED }
