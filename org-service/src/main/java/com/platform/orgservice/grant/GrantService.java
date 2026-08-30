package com.platform.orgservice.grant;

import com.platform.orgservice.common.NotFoundException;
import com.platform.orgservice.domain.GrantAudit;
import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.domain.SubjectType;
import com.platform.orgservice.grant.dto.GrantAuditResponse;
import com.platform.orgservice.grant.dto.GrantCreateRequest;
import com.platform.orgservice.grant.dto.GrantDetailResponse;
import com.platform.orgservice.permission.PermissionFacade;
import com.platform.orgservice.repository.GrantAuditRepository;
import com.platform.orgservice.repository.GrantEntryRepository;
import com.platform.orgservice.repository.MemberRepository;
import com.platform.orgservice.repository.TeamRepository;
import org.springframework.data.domain.Limit;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class GrantService {

    /** 한 리소스에 대해 돌려주는 감사 기록 수 — 훑어보는 목록이라 더 길면 끝까지 읽지 않는다. */
    public static final int AUDIT_PAGE_SIZE = 100;

    private final GrantEntryRepository grants;
    private final PermissionFacade permissions;
    private final GrantAuditRepository audits;
    private final MemberRepository members;
    private final TeamRepository teams;

    @Transactional(readOnly = true)
    public List<GrantDetailResponse> listByResource(long actorId, ResourceKind resourceType, String resourceId) {
        // 전역 관리자 또는 그 리소스의 ADMIN — 스페이스 소유자가 자기 공간 권한을 못 보면
        // 초대·회수를 아예 할 수 없다(컨플루언스 스페이스 관리자와 같은 범위).
        permissions.requireResourceAdmin(actorId, resourceType, resourceId);
        return grants.findByResourceTypeAndResourceId(resourceType, resourceId == null ? "" : resourceId)
                .stream().map(GrantDetailResponse::from).toList();
    }

    public GrantDetailResponse create(long actorId, GrantCreateRequest req) {
        String resourceId = req.resourceType() == ResourceKind.GLOBAL ? "" : req.resourceId();
        permissions.requireResourceAdmin(actorId, req.resourceType(), resourceId);
        try {
            GrantEntry saved = grants.saveAndFlush(GrantEntry.of(
                    req.subjectType(), req.subjectId(), req.resourceType(), resourceId, req.role()));
            audits.save(GrantAudit.of(actorId, GrantAudit.Action.GRANTED, saved, labelOf(saved)));
            return GrantDetailResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("이미 존재하는 grant (subject·resource 조합 중복)");
        }
    }

    public void delete(long actorId, long grantId) {
        // 무엇을 지우는지 먼저 읽어야 "그 리소스의 관리자인가"를 물을 수 있다.
        GrantEntry target = grants.findById(grantId)
                .orElseThrow(() -> new NotFoundException("grant 없음: " + grantId));
        permissions.requireResourceAdmin(actorId, target.getResourceType(), target.getResourceId());
        // 지우기 전에 남긴다 — 지운 뒤에는 무엇이 회수됐는지 읽을 수 없다.
        audits.save(GrantAudit.of(actorId, GrantAudit.Action.REVOKED, target, labelOf(target)));
        grants.deleteById(grantId);
    }

    /**
     * 권한 변경 이력 — 조회 범위는 grant 목록과 같다(그 리소스의 ADMIN).
     *
     * 감사에서 가장 궁금한 것이 "누가 이 사람에게 권한을 줬나"인데, 그 조작은 여기서 일어나고
     * wiki-backend는 보지 못한다. 화면은 두 기록을 합쳐 하나의 목록으로 보여준다.
     */
    @Transactional(readOnly = true)
    public List<GrantAuditResponse> auditByResource(long actorId, ResourceKind resourceType, String resourceId) {
        permissions.requireResourceAdmin(actorId, resourceType, resourceId);
        return audits.findByResource(resourceType, resourceId == null ? "" : resourceId,
                        Limit.of(AUDIT_PAGE_SIZE))
                .stream().map(GrantAuditResponse::from).toList();
    }

    /**
     * 기록 시점의 대상 이름. 못 찾으면 id로 대신한다 — 이름을 못 읽는다고 권한 조작을
     * 실패시킬 이유는 없다.
     */
    private String labelOf(GrantEntry grant) {
        if (grant.getSubjectType() == SubjectType.TEAM) {
            return teams.findById(grant.getSubjectId())
                    .map(t -> t.getName())
                    .orElse("팀 #" + grant.getSubjectId());
        }
        return members.findById(grant.getSubjectId())
                .map(m -> m.getDisplayName())
                .orElse("사용자 #" + grant.getSubjectId());
    }
}
