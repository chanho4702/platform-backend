package com.platform.orgservice.grant;

import com.platform.orgservice.common.NotFoundException;
import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.grant.dto.GrantCreateRequest;
import com.platform.orgservice.grant.dto.GrantDetailResponse;
import com.platform.orgservice.permission.PermissionFacade;
import com.platform.orgservice.repository.GrantEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class GrantService {

    private final GrantEntryRepository grants;
    private final PermissionFacade permissions;

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
        grants.deleteById(grantId);
    }
}
