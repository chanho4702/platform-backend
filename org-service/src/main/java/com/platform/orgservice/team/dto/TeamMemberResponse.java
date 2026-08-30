package com.platform.orgservice.team.dto;

/** 팀원 한 줄 — 이름을 함께 준다. 화면이 멤버 디렉터리를 다시 뒤지지 않게 한다. */
public record TeamMemberResponse(Long memberId, String displayName, String role) {}
