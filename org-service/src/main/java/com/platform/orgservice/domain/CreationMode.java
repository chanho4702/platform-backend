package com.platform.orgservice.domain;

/**
 * 스페이스·프로젝트를 만들 수 있는 사람(전역 설정).
 *
 * <ul>
 *   <li>{@code ALL_ACTIVE} — 활성 구성원 전체(기본, 컨플루언스 기본과 같다)</li>
 *   <li>{@code GLOBAL_ADMIN} — 전역 관리자만</li>
 *   <li>{@code TEAMS} — 지정한 팀의 구성원(+ 전역 관리자)</li>
 * </ul>
 * 어느 모드든 ACTIVE가 아닌 계정(승인 대기·정지·퇴사·구성원 행 없음)은 만들 수 없다.
 */
public enum CreationMode { ALL_ACTIVE, GLOBAL_ADMIN, TEAMS }
