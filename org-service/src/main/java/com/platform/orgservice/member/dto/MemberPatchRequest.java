package com.platform.orgservice.member.dto;

import com.platform.orgservice.domain.MemberStatus;
import jakarta.validation.constraints.NotNull;

/**
 * 상태 전이. 표시 이름은 여기서 바꾸지 않는다 — {@code MemberMirrorFilter}가 요청마다 JWT의
 * {@code name}으로 덮어쓰므로 고쳐 봐야 다음 로그인에 되돌아간다(원천은 Keycloak 프로필).
 */
public record MemberPatchRequest(@NotNull(message = "변경할 상태를 지정하세요") MemberStatus status) {}
