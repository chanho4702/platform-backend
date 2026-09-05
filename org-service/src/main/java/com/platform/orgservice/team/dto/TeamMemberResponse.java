package com.platform.orgservice.team.dto;

/**
 * 팀원 한 줄 — 이름과 이메일을 함께 준다. 화면이 멤버 디렉터리를 다시 뒤지지 않게 한다.
 *
 * <p>{@code email}은 동명이인을 가르는 유일한 단서다. 팀원 목록에서 사람을 지목하는 화면
 * (리더 지정·제외)이 이름만 보고는 누구인지 확신할 수 없다. 없으면 null이다.
 */
public record TeamMemberResponse(Long memberId, String displayName, String email, String role) {}
