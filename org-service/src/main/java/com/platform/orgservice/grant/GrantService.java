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
        permissions.requireGlobalAdmin(actorId);
        return grants.findByResourceTypeAndResourceId(resourceType, resourceId == null ? "" : resourceId)
                .stream().map(GrantDetailResponse::from).toList();
    }

    public GrantDetailResponse create(long actorId, GrantCreateRequest req) {
        permissions.requireGlobalAdmin(actorId);
        try {
            GrantEntry saved = grants.saveAndFlush(GrantEntry.of(
                    req.subjectType(), req.subjectId(), req.resourceType(), req.resourceId(), req.role()));
            return GrantDetailResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("이미 존재하는 grant (subject·resource 조합 중복)");
        }
    }

    public void delete(long actorId, long grantId) {
        permissions.requireGlobalAdmin(actorId);
        if (!grants.existsById(grantId)) throw new NotFoundException("grant 없음: " + grantId);
        grants.deleteById(grantId);
    }
}
