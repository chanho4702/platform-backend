package com.platform.orgservice.grpc;

import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.GrantRole;
import com.platform.orgservice.domain.PermAction;
import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.domain.SubjectType;
import com.platform.orgservice.permission.PermissionFacade;
import com.platform.orgservice.repository.GrantEntryRepository;
import com.platform.proto.org.v1.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** platform.org.v1.PermissionService 구현 — 판정 로직은 PermissionFacade에 위임. */
@Service
@RequiredArgsConstructor
public class PermissionGrpcService extends PermissionServiceGrpc.PermissionServiceImplBase {

    private final PermissionFacade permissions;
    private final GrantEntryRepository grants;

    @Override
    public void checkPermission(CheckPermissionRequest req, StreamObserver<CheckPermissionResponse> out) {
        ResourceKind kind = toKind(req.getResourceType());
        PermAction action = toAction(req.getAction());
        if (kind == null || action == null) {
            out.onError(Status.INVALID_ARGUMENT
                    .withDescription("resource_type/action은 UNSPECIFIED일 수 없습니다").asRuntimeException());
            return;
        }
        PermissionFacade.Decision d = permissions.check(req.getUserId(), kind, req.getResourceId(), action);
        out.onNext(CheckPermissionResponse.newBuilder()
                .setAllowed(d.allowed())
                .setEffectiveRole(toProtoRole(d.effectiveRole()))
                .build());
        out.onCompleted();
    }

    @Override
    public void listUserGrants(ListUserGrantsRequest req, StreamObserver<ListUserGrantsResponse> out) {
        ResourceKind kind = toKind(req.getResourceType()); // UNSPECIFIED → null → 전체
        ListUserGrantsResponse.Builder res = ListUserGrantsResponse.newBuilder();
        permissions.grantsOf(req.getUserId(), kind).forEach(g -> res.addGrants(Grant.newBuilder()
                .setResourceType(toProtoKind(g.getResourceType()))
                .setResourceId(g.getResourceId())
                .setRole(toProtoRole(g.getRole()))
                .build()));
        out.onNext(res.build());
        out.onCompleted();
    }

    @Override
    public void createGrant(CreateGrantRequest req, StreamObserver<CreateGrantResponse> out) {
        ResourceKind kind = toKind(req.getResourceType());
        GrantRole role = toDomainRole(req.getRole());
        if (kind == null || role == null) {
            out.onError(Status.INVALID_ARGUMENT
                    .withDescription("resource_type/role은 UNSPECIFIED일 수 없습니다").asRuntimeException());
            return;
        }
        String resourceId = kind == ResourceKind.GLOBAL ? "" : req.getResourceId();
        boolean created = grants.findBySubjectTypeAndSubjectIdAndResourceTypeAndResourceId(
                        SubjectType.USER, req.getUserId(), kind, resourceId)
                .isEmpty();
        if (created) {
            grants.save(GrantEntry.of(SubjectType.USER, req.getUserId(), kind, resourceId, role));
        }
        out.onNext(CreateGrantResponse.newBuilder().setCreated(created).build());
        out.onCompleted();
    }

    private static ResourceKind toKind(ResourceType t) {
        return switch (t) {
            case GLOBAL -> ResourceKind.GLOBAL;
            case SPACE -> ResourceKind.SPACE;
            case PROJECT -> ResourceKind.PROJECT;
            default -> null;
        };
    }

    private static PermAction toAction(Action a) {
        return switch (a) {
            case VIEW -> PermAction.VIEW;
            case EDIT -> PermAction.EDIT;
            case ADMIN -> PermAction.ADMIN;
            default -> null;
        };
    }

    /** proto Role → 도메인 GrantRole (ROLE_ADMIN ↔ ADMIN 비대칭 유지). */
    private static GrantRole toDomainRole(Role r) {
        return switch (r) {
            case VIEWER -> GrantRole.VIEWER;
            case EDITOR -> GrantRole.EDITOR;
            case ROLE_ADMIN -> GrantRole.ADMIN;
            default -> null;
        };
    }

    private static ResourceType toProtoKind(ResourceKind k) {
        return switch (k) {
            case GLOBAL -> ResourceType.GLOBAL;
            case SPACE -> ResourceType.SPACE;
            case PROJECT -> ResourceType.PROJECT;
        };
    }

    /** proto Role.ROLE_ADMIN ↔ 도메인 GrantRole.ADMIN (proto enum 스코프 충돌 회피 명명). */
    private static Role toProtoRole(GrantRole r) {
        if (r == null) return Role.ROLE_UNSPECIFIED;
        return switch (r) {
            case VIEWER -> Role.VIEWER;
            case EDITOR -> Role.EDITOR;
            case ADMIN -> Role.ROLE_ADMIN;
        };
    }
}
