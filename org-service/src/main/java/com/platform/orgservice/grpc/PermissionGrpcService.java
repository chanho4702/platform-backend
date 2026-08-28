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

import java.util.List;
import java.util.Objects;

/** platform.org.v1.PermissionService 구현 — 판정 로직은 PermissionFacade에 위임. */
@Service
@RequiredArgsConstructor
public class PermissionGrpcService extends PermissionServiceGrpc.PermissionServiceImplBase {

    private final PermissionFacade permissions;
    private final GrantEntryRepository grants;
    private final com.platform.orgservice.repository.TeamMemberRepository teamMembers;
    private final com.platform.orgservice.repository.MemberRepository members;
    private final com.platform.orgservice.repository.TeamRepository teams;

    @Override
    public void listUserTeams(ListUserTeamsRequest req, StreamObserver<ListUserTeamsResponse> out) {
        // W18 페이지 제한의 TEAM 주체 판정 — wiki가 30초 캐시로 감싼다(권한 판정과 같은 지연 특성)
        out.onNext(ListUserTeamsResponse.newBuilder()
                .addAllTeamIds(teamMembers.findTeamIdsByMemberId(req.getUserId()))
                .build());
        out.onCompleted();
    }

    @Override
    public void validatePrincipals(ValidatePrincipalsRequest req,
                                   StreamObserver<ValidatePrincipalsResponse> out) {
        ValidatePrincipalsResponse.Builder response = ValidatePrincipalsResponse.newBuilder();
        for (PrincipalRef principal : req.getPrincipalsList()) {
            boolean exists = principal.getId() > 0 && switch (principal.getKind()) {
                case PRINCIPAL_USER -> members.existsById(principal.getId());
                case PRINCIPAL_TEAM -> teams.existsById(principal.getId());
                default -> false;
            };
            if (!exists) response.addMissing(principal);
        }
        out.onNext(response.build());
        out.onCompleted();
    }

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

    /**
     * 리소스가 사라졌을 때 그 리소스에 걸린 grant를 정리한다 — 없으면 고아 grant가 남아,
     * 같은 id가 재사용될 때 예전 사용자에게 권한이 되살아난다.
     * user_id를 지정하면 그 사용자 것만. 대상이 없어도 에러가 아니다(삭제는 재시도된다).
     */
    @Override
    public void revokeGrant(RevokeGrantRequest req, StreamObserver<RevokeGrantResponse> out) {
        ResourceKind kind = toKind(req.getResourceType());
        if (kind == null) {
            out.onError(Status.INVALID_ARGUMENT
                    .withDescription("resource_type은 UNSPECIFIED일 수 없습니다").asRuntimeException());
            return;
        }
        String resourceId = kind == ResourceKind.GLOBAL ? "" : req.getResourceId();
        List<GrantEntry> targets = grants.findByResourceTypeAndResourceId(kind, resourceId);
        if (req.getUserId() != 0L) {
            targets = targets.stream()
                    .filter(g -> g.getSubjectType() == SubjectType.USER
                            && Objects.equals(g.getSubjectId(), req.getUserId()))
                    .toList();
        }
        grants.deleteAll(targets);
        out.onNext(RevokeGrantResponse.newBuilder().setRevoked(targets.size()).build());
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
