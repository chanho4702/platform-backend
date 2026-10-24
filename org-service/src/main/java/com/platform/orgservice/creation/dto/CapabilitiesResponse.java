package com.platform.orgservice.creation.dto;

/** 내가 지금 새로 만들 수 있는 것 — 화면이 "스페이스 만들기"·"프로젝트 만들기" 버튼을 숨기는 데 쓴다. */
public record CapabilitiesResponse(boolean canCreateSpace, boolean canCreateProject) {}
