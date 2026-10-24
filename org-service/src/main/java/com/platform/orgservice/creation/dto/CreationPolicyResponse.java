package com.platform.orgservice.creation.dto;

import com.platform.orgservice.domain.CreationMode;

import java.time.Instant;
import java.util.List;

/**
 * 생성 가능 대상 설정. 한 번도 저장하지 않았으면 {@code mode=ALL_ACTIVE}, 빈 {@code teamIds},
 * {@code updatedBy}/{@code updatedAt} null. {@code teamIds}는 숫자(Long) 배열이다.
 */
public record CreationPolicyResponse(Entry space, Entry project) {

    public record Entry(CreationMode mode, List<Long> teamIds, Long updatedBy, Instant updatedAt) {}
}
