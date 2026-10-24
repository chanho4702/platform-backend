package com.platform.orgservice.grpc;

import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.GrantRole;
import com.platform.orgservice.domain.PermAction;
import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.domain.SubjectType;
import com.platform.orgservice.creation.CreationPolicyService;
import com.platform.orgservice.permission.PermissionFacade;
import com.platform.orgservice.repository.GrantEntryRepository;
import com.platform.proto.org.v1.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.platform.orgservice.domain.Member;
import com.platform.orgservice.domain.MemberStatus;
import com.platform.orgservice.domain.Team;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** platform.org.v1.PermissionService 구현 — 판정 로직은 PermissionFacade에 위임. */
@Service
@RequiredArgsConstructor
public class PermissionGrpcService extends PermissionServiceGrpc.PermissionServiceImplBase {

    private final PermissionFacade permissions;
    private final GrantEntryRepository grants;
    private final com.platform.orgservice.repository.TeamMemberRepository teamMembers;
    private final com.platform.orgservice.repository.MemberRepository members;
    private final com.platform.orgservice.repository.TeamRepository teams;
    private final CreationPolicyService creation;

    /** 한 요청의 조회 항목 상한 — 넘으면 INVALID_ARGUMENT. 이관은 항목 단위로 쪼개서 부른다. */
    private static final int LOOKUP_LIMIT = 200;

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

    /**
     * 이름으로 우리 계정을 찾는다(0.15.0). 판정이 아니라 조회다 — 이관이 원본 작성자·제한 주체를
     * 짝지을 때 쓴다.
     *
     * 규칙 셋:
     * 1. **활성 계정만** 본다. 퇴사자에게 문서를 붙이면 아무도 손댈 수 없는 문서가 생긴다.
     * 2. **후보가 둘 이상이면 매칭이 아니다.** member.email에 UNIQUE가 없어 같은 이메일·같은
     *    local-part가 둘일 수 있는데, 그중 하나를 고르면 남의 이름으로 문서가 쓰인다.
     * 3. 이메일이 먼저다. 같은 문자열이 emails·usernames에 다 실리면 이메일 매칭만 남긴다.
     */
    @Override
    public void lookupMembers(LookupMembersRequest req, StreamObserver<LookupMembersResponse> out) {
        if (req.getEmailsCount() + req.getUsernamesCount() > LOOKUP_LIMIT) {
            out.onError(Status.INVALID_ARGUMENT
                    .withDescription("한 번에 조회할 수 있는 항목은 " + LOOKUP_LIMIT + "개까지입니다")
                    .asRuntimeException());
            return;
        }
        Map<String, List<String>> byEmail = normalize(req.getEmailsList());
        Map<String, List<String>> byUsername = normalize(req.getUsernamesList());

        Map<String, Member> emailHits = unique(byEmail.isEmpty() ? List.of()
                : members.findByStatusAndEmailInIgnoreCase(MemberStatus.ACTIVE, byEmail.keySet()),
                member -> lower(member.getEmail()));
        Map<String, Member> usernameHits = unique(byUsername.isEmpty() ? List.of()
                : members.findByStatusAndEmailLocalPartInIgnoreCase(MemberStatus.ACTIVE, byUsername.keySet()),
                member -> localPart(member.getEmail()));

        LookupMembersResponse.Builder response = LookupMembersResponse.newBuilder();
        Set<String> answered = new LinkedHashSet<>();
        emit(byEmail, emailHits, answered, response);
        emit(byUsername, usernameHits, answered, response);
        out.onNext(response.build());
        out.onCompleted();
    }

    /** 원본 그룹 이름 → 우리 팀. LookupMembers와 같은 규칙(활성 개념은 팀에 없다, 중복은 미매칭). */
    @Override
    public void lookupTeams(LookupTeamsRequest req, StreamObserver<LookupTeamsResponse> out) {
        if (req.getNamesCount() > LOOKUP_LIMIT) {
            out.onError(Status.INVALID_ARGUMENT
                    .withDescription("한 번에 조회할 수 있는 항목은 " + LOOKUP_LIMIT + "개까지입니다")
                    .asRuntimeException());
            return;
        }
        Map<String, List<String>> byName = normalize(req.getNamesList());
        Map<String, Team> hits = unique(byName.isEmpty() ? List.of()
                : teams.findByNameInIgnoreCase(byName.keySet()), team -> lower(team.getName()));

        LookupTeamsResponse.Builder response = LookupTeamsResponse.newBuilder();
        Set<String> answered = new LinkedHashSet<>();
        byName.forEach((key, queries) -> {
            Team team = hits.get(key);
            if (team == null) return;
            for (String query : queries) {
                if (answered.add(query)) {
                    response.addMatches(TeamMatch.newBuilder()
                            .setQuery(query)
                            .setTeamId(team.getId())
                            .setName(team.getName())
                            .build());
                }
            }
        });
        out.onNext(response.build());
        out.onCompleted();
    }

    private static void emit(Map<String, List<String>> requested, Map<String, Member> hits,
                             Set<String> answered, LookupMembersResponse.Builder response) {
        requested.forEach((key, queries) -> {
            Member member = hits.get(key);
            if (member == null) return;
            for (String query : queries) {
                if (answered.add(query)) {
                    response.addMatches(MemberMatch.newBuilder()
                            .setQuery(query)
                            .setMemberId(member.getId())
                            .setDisplayName(member.getDisplayName() == null ? "" : member.getDisplayName())
                            .setEmail(member.getEmail() == null ? "" : member.getEmail())
                            .build());
                }
            }
        });
    }

    /**
     * 요청 문자열 → 대조 키(trim + 소문자). 응답의 query는 요청에 실렸던 원문 그대로여야 해서
     * 키 하나에 원문 여러 개가 달릴 수 있다(대소문자만 다른 값을 함께 보낸 경우).
     */
    private static Map<String, List<String>> normalize(List<String> raw) {
        Map<String, List<String>> byKey = new LinkedHashMap<>();
        for (String value : raw) {
            if (value == null) continue;
            String key = value.trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) continue;
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
        return byKey;
    }

    /** 키가 겹치는 후보는 통째로 버린다 — "둘 중 누구인지 모른다"는 매칭이 아니다. */
    private static <T> Map<String, T> unique(List<T> rows, java.util.function.Function<T, String> keyOf) {
        Map<String, T> byKey = new LinkedHashMap<>();
        Set<String> ambiguous = new LinkedHashSet<>();
        for (T row : rows) {
            String key = keyOf.apply(row);
            if (key == null) continue;
            if (byKey.putIfAbsent(key, row) != null) ambiguous.add(key);
        }
        ambiguous.forEach(byKey::remove);
        return byKey;
    }

    private static String lower(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    /** 이메일의 '@' 앞부분. 규칙은 repository 질의와 같아야 한다. */
    private static String localPart(String email) {
        String value = lower(email);
        if (value == null) return null;
        int at = value.indexOf('@');
        return at <= 0 ? null : value.substring(0, at);
    }

    /**
     * 권한 판정.
     *
     * <p>계정 상태가 ACTIVE가 아니면 grant를 보기 전에 거부한다(fail-closed). 승인 대기·정지·퇴사자는
     * 권한을 그대로 들고 있을 수 있는데, 그 권한이 살아 있으면 wiki·alm은 아무 일도 없었던 것처럼
     * 문서를 열어 준다. 상태 판정을 org 한 곳에 두면 소비 서비스가 각자 기억할 필요가 없다.
     *
     * <p><b>member 행이 없으면 PENDING으로 본다</b>(권한·계정 점검 Top 5-①, 2026-10-24). 예전에는 "아직 org REST를
     * 거치지 않은 새 사용자"라며 평소대로 grant를 봤는데, 그 틈으로 승인 대기 격리를 건너뛰고 API를 직접 불러
     * 스페이스를 만든 뒤 ADMIN을 얻는 우회가 확인됐다. 이제 행은 로그인 순간 auth-server가
     * {@code POST /internal/org/members/ensure}로 보장하고, 배포 전부터 있던 계정은 런북 백필이 채운다.
     * 에이전트 페르소나는 agent-service 등록이 ACTIVE 행을 만든다.
     *
     * <p>{@code resource_type=GLOBAL}, {@code resource_id="create:SPACE"|"create:PROJECT"}는 "새로 만들 수 있는가"
     * 판정이다(Top 5-③) — grant가 아니라 전역 생성 설정({@link CreationPolicyService})을 본다. action은 보지 않는다
     * (계약상 EDIT를 보낸다). 대상이 틀리면 INVALID_ARGUMENT.
     */
    @Override
    public void checkPermission(CheckPermissionRequest req, StreamObserver<CheckPermissionResponse> out) {
        ResourceKind kind = toKind(req.getResourceType());
        PermAction action = toAction(req.getAction());
        if (kind == null || action == null) {
            out.onError(Status.INVALID_ARGUMENT
                    .withDescription("resource_type/action은 UNSPECIFIED일 수 없습니다").asRuntimeException());
            return;
        }
        java.util.Optional<ResourceKind> createTarget;
        try {
            createTarget = kind == ResourceKind.GLOBAL
                    ? CreationPolicyService.parseCreateTarget(req.getResourceId())
                    : java.util.Optional.empty();
        } catch (IllegalArgumentException e) {
            out.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            return;
        }
        MemberStatus status = statusOf(req.getUserId());
        if (status != MemberStatus.ACTIVE) {
            // 정상적으로 판정한 거부다 — 오류 상태(UNAVAILABLE 등)로 올리지 않는다.
            // 소비자가 "org가 죽었다"와 "이 사람이 막혔다"를 구분할 수 있어야 한다.
            out.onNext(CheckPermissionResponse.newBuilder()
                    .setAllowed(false)
                    .setEffectiveRole(Role.ROLE_UNSPECIFIED)
                    .setDeniedReason(status.name())
                    .build());
            out.onCompleted();
            return;
        }
        if (createTarget.isPresent()) {
            CreationPolicyService.Decision c = creation.canCreate(req.getUserId(), createTarget.get());
            out.onNext(CheckPermissionResponse.newBuilder()
                    .setAllowed(c.allowed())
                    .setEffectiveRole(Role.ROLE_UNSPECIFIED)
                    .setDeniedReason(c.deniedReason())
                    .build());
            out.onCompleted();
            return;
        }
        PermissionFacade.Decision d = permissions.check(req.getUserId(), kind, req.getResourceId(), action);
        out.onNext(CheckPermissionResponse.newBuilder()
                .setAllowed(d.allowed())
                .setEffectiveRole(toProtoRole(d.effectiveRole()))
                .setDeniedReason(deniedReason(d))
                .build());
        out.onCompleted();
    }

    /**
     * 거부 사유(0.16.0). 허용이면 빈 문자열.
     *
     * grant가 아예 없는 것과 역할이 모자란 것을 나눈다 — 화면이 사용자에게 해야 할 말이 다르다
     * ("권한을 요청하세요" vs "편집 권한이 필요합니다").
     */
    private static String deniedReason(PermissionFacade.Decision d) {
        if (d.allowed()) return "";
        return d.effectiveRole() == null ? "NO_GRANT" : "INSUFFICIENT_ROLE";
    }

    /**
     * 사용자의 grant 목록(검색의 접근 가능 스페이스 계산 등).
     *
     * <p>{@link #checkPermission}과 같은 기준으로 계정 상태를 먼저 본다(P-02): ACTIVE가 아니면 grant를 그대로
     * 들고 있어도 빈 목록이다. member 행이 없으면 PENDING과 같다(Top 5-①) — checkPermission과 같은 이유.
     */
    @Override
    public void listUserGrants(ListUserGrantsRequest req, StreamObserver<ListUserGrantsResponse> out) {
        ResourceKind kind = toKind(req.getResourceType()); // UNSPECIFIED → null → 전체
        ListUserGrantsResponse.Builder res = ListUserGrantsResponse.newBuilder();
        MemberStatus status = statusOf(req.getUserId());
        if (status != MemberStatus.ACTIVE) {
            out.onNext(res.build());
            out.onCompleted();
            return;
        }
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

    /** 행이 없으면 PENDING — 초대·승인·부트스트랩 어느 것도 거치지 않은 계정이다(checkPermission 주석 참고). */
    private MemberStatus statusOf(long userId) {
        return members.findById(userId).map(Member::getStatus).orElse(MemberStatus.PENDING);
    }

    /**
     * id로 사람을 읽는다(0.16.0). {@link #lookupMembers}와 방향이 반대다 — 여기는 id를 아는 쪽이
     * 이름·이메일을 묻는다(ALM 알림이 담당자 주소를 얻는 창구).
     *
     * <p>LookupMembers와 달리 <b>활성 여부로 거르지 않는다.</b> 퇴사자에게 알림을 보내지 않는 것과
     * 퇴사자 이름을 화면에 못 그리는 것은 다른 문제라, 상태를 실어 주고 판단은 호출측에 맡긴다.
     * 없는 id는 응답에서 빠진다.
     */
    @Override
    public void getMembers(GetMembersRequest req, StreamObserver<GetMembersResponse> out) {
        if (req.getIdsCount() > LOOKUP_LIMIT) {
            out.onError(Status.INVALID_ARGUMENT
                    .withDescription("한 번에 조회할 수 있는 항목은 " + LOOKUP_LIMIT + "개까지입니다")
                    .asRuntimeException());
            return;
        }
        List<Long> ids = req.getIdsList().stream().distinct().toList();
        GetMembersResponse.Builder response = GetMembersResponse.newBuilder();
        if (!ids.isEmpty()) {
            members.findAllById(ids).forEach(m -> response.addMembers(MemberInfo.newBuilder()
                    .setId(m.getId())
                    .setDisplayName(m.getDisplayName() == null ? "" : m.getDisplayName())
                    .setEmail(m.getEmail() == null ? "" : m.getEmail())
                    .setStatus(m.getStatus().name())
                    .setKind(m.getKind().name())
                    .build()));
        }
        out.onNext(response.build());
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
            case COMMENT -> PermAction.COMMENT;
            case EDIT -> PermAction.EDIT;
            case ADMIN -> PermAction.ADMIN;
            default -> null;
        };
    }

    /** proto Role → 도메인 GrantRole (ROLE_ADMIN ↔ ADMIN 비대칭 유지). */
    private static GrantRole toDomainRole(Role r) {
        return switch (r) {
            case VIEWER -> GrantRole.VIEWER;
            case COMMENTER -> GrantRole.COMMENTER;
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
            case COMMENTER -> Role.COMMENTER;
            case EDITOR -> Role.EDITOR;
            case ADMIN -> Role.ROLE_ADMIN;
        };
    }
}
