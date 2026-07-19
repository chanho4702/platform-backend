package com.platform.orgservice.permission;

import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.GrantRole;
import com.platform.orgservice.domain.PermAction;
import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.repository.GrantEntryRepository;
import com.platform.orgservice.repository.TeamMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/** 권한 판정 단일 진입점 — REST 가드·gRPC PermissionService가 공용. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PermissionFacade {

    private final GrantEntryRepository grants;
    private final TeamMemberRepository teamMembers;

    /** effectiveRole은 grant가 하나도 없으면 null. */
    public record Decision(boolean allowed, GrantRole effectiveRole) {}

    public Decision check(long userId, ResourceKind kind, String resourceId, PermAction action) {
        GrantRole effective = grants.findEffective(userId, teamIdsOf(userId), kind, nullToEmpty(resourceId))
                .stream()
                .map(GrantEntry::getRole)
                .max(Comparator.comparingInt(GrantRole::rank))
                .orElse(null);
        return new Decision(effective != null && effective.covers(action), effective);
    }

    /** 내 grant 목록 — kind가 null이면 전체. /me/permissions·ListUserGrants 공용. */
    public List<GrantEntry> grantsOf(long userId, ResourceKind kind) {
        List<Long> teamIds = teamIdsOf(userId);
        return kind == null
                ? grants.findAllForUser(userId, teamIds)
                : grants.findAllForUserByKind(userId, teamIds, kind);
    }

    public void requireGlobalAdmin(long userId) {
        if (!check(userId, ResourceKind.GLOBAL, "", PermAction.ADMIN).allowed()) {
            throw new AccessDeniedException("GLOBAL ADMIN 권한이 필요합니다");
        }
    }

    private List<Long> teamIdsOf(long userId) {
        List<Long> ids = teamMembers.findTeamIdsByMemberId(userId);
        return ids.isEmpty() ? List.of(-1L) : ids; // 빈 IN 절 회피
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
}
