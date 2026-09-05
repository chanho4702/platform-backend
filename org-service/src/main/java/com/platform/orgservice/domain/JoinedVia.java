package com.platform.orgservice.domain;

/**
 * 이 사람이 어떻게 활성 사용자가 됐는가.
 *
 * <p>LEGACY는 초대 제도가 생기기 전부터 있던 계정이다 — 되짚어 초대 기록을 만들어 낼 수 없으므로
 * 그대로 둔다. BOOTSTRAP은 {@code PLATFORM_BOOTSTRAP_ADMIN_ID} 시드다.
 */
public enum JoinedVia { INVITE, APPROVAL, BOOTSTRAP, LEGACY }
