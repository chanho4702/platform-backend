package com.platform.orgservice.creation;

import com.platform.common.error.NotFoundException;
import com.platform.orgservice.creation.dto.CreationPolicyResponse;
import com.platform.orgservice.creation.dto.CreationPolicyUpdateRequest;
import com.platform.orgservice.domain.CreationMode;
import com.platform.orgservice.domain.CreationPolicy;
import com.platform.orgservice.domain.Member;
import com.platform.orgservice.domain.MemberStatus;
import com.platform.orgservice.domain.PermAction;
import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.permission.PermissionFacade;
import com.platform.orgservice.repository.CreationPolicyRepository;
import com.platform.orgservice.repository.MemberRepository;
import com.platform.orgservice.repository.TeamMemberRepository;
import com.platform.orgservice.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * "누가 스페이스·프로젝트를 새로 만들 수 있는가" — 판정과 설정(권한·계정 점검 Top 5-③).
 *
 * <p>판정 순서는 하나로 고정한다: ① 계정 상태(ACTIVE가 아니거나 구성원 행이 없으면 거부 — CheckPermission과 같은 기준)
 * ② 전역 관리자는 언제나 허용 ③ 설정 모드. 상태를 먼저 보는 이유는, 승인 대기 계정이 공간을 만들어 그 ADMIN이 되면
 * 그 grant로 초대·권한 관리까지 열리기 때문이다(점검 B1).
 *
 * <p>wiki·alm은 gRPC {@code CheckPermission(resource_type=GLOBAL, resource_id="create:SPACE"|"create:PROJECT")}로 묻고,
 * 화면은 {@code GET /api/org/me/capabilities}로 버튼을 숨긴다. 둘 다 이 클래스 하나를 본다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CreationPolicyService {

    /** gRPC CheckPermission에서 생성 판정을 가리키는 GLOBAL resource_id 접두사. */
    public static final String CREATE_PREFIX = "create:";

    /** 상태는 ACTIVE인데 설정이 막은 거부. */
    public static final String DENIED_RESTRICTED = "CREATE_RESTRICTED";

    private final CreationPolicyRepository policies;
    private final MemberRepository members;
    private final TeamMemberRepository teamMembers;
    private final TeamRepository teams;
    private final PermissionFacade permissions;

    /** @param deniedReason 허용이면 빈 문자열, 상태 거부면 상태 이름(PENDING 등), 설정 거부면 CREATE_RESTRICTED */
    public record Decision(boolean allowed, String deniedReason) {
        static final Decision ALLOWED = new Decision(true, "");
    }

    /**
     * {@code "create:SPACE"} → SPACE. 접두사가 아니면 빈 값(평소의 GLOBAL 판정),
     * 접두사인데 대상이 틀리면 {@link IllegalArgumentException}(호출측 버그 — gRPC는 INVALID_ARGUMENT).
     */
    public static Optional<ResourceKind> parseCreateTarget(String resourceId) {
        if (resourceId == null || !resourceId.startsWith(CREATE_PREFIX)) return Optional.empty();
        String target = resourceId.substring(CREATE_PREFIX.length()).trim().toUpperCase(Locale.ROOT);
        return switch (target) {
            case "SPACE" -> Optional.of(ResourceKind.SPACE);
            case "PROJECT" -> Optional.of(ResourceKind.PROJECT);
            default -> throw new IllegalArgumentException(
                    "생성 대상은 create:SPACE 또는 create:PROJECT입니다: " + resourceId);
        };
    }

    public Decision canCreate(long userId, ResourceKind kind) {
        requireCreatable(kind);
        // 구성원 행이 없으면 승인 대기와 같다(Top 5-①) — 초대·승인을 한 번도 거치지 않은 계정이다.
        MemberStatus status = members.findById(userId).map(Member::getStatus).orElse(MemberStatus.PENDING);
        if (status != MemberStatus.ACTIVE) return new Decision(false, status.name());
        if (permissions.check(userId, ResourceKind.GLOBAL, "", PermAction.ADMIN).allowed()) return Decision.ALLOWED;

        CreationPolicy policy = policies.findById(kind).orElse(null);
        CreationMode mode = policy == null ? CreationMode.ALL_ACTIVE : policy.getMode();
        boolean allowed = switch (mode) {
            case ALL_ACTIVE -> true;
            case GLOBAL_ADMIN -> false;
            case TEAMS -> teamMembers.findTeamIdsByMemberId(userId).stream()
                    .anyMatch(policy.getTeamIds()::contains);
        };
        return allowed ? Decision.ALLOWED : new Decision(false, DENIED_RESTRICTED);
    }

    // ---------------------------------------------------------------- 설정(전역 관리자)

    public CreationPolicyResponse view(long actorId) {
        permissions.requireGlobalAdmin(actorId);
        return current();
    }

    @Transactional
    public CreationPolicyResponse update(long actorId, CreationPolicyUpdateRequest req) {
        permissions.requireGlobalAdmin(actorId);
        if (req == null || (req.space() == null && req.project() == null)) {
            throw new IllegalArgumentException("space 또는 project 설정을 보내세요");
        }
        if (req.space() != null) apply(actorId, ResourceKind.SPACE, req.space());
        if (req.project() != null) apply(actorId, ResourceKind.PROJECT, req.project());
        policies.flush();
        return current();
    }

    private void apply(long actorId, ResourceKind kind, CreationPolicyUpdateRequest.Entry entry) {
        CreationMode mode = entry.mode();
        if (mode == null) throw new IllegalArgumentException("mode는 ALL_ACTIVE·GLOBAL_ADMIN·TEAMS 중 하나여야 합니다");
        Set<Long> teamIds = new LinkedHashSet<>(entry.teamIds() == null ? List.of() : entry.teamIds());
        if (mode == CreationMode.TEAMS) {
            if (teamIds.isEmpty()) throw new IllegalArgumentException("팀을 하나 이상 지정하세요");
            for (Long teamId : teamIds) {
                if (teamId == null || !teams.existsById(teamId)) throw new NotFoundException("팀 없음: " + teamId);
            }
        }
        CreationPolicy policy = policies.findById(kind).orElseGet(() -> CreationPolicy.of(kind));
        policy.change(mode, teamIds, actorId);
        policies.save(policy);
    }

    private CreationPolicyResponse current() {
        return new CreationPolicyResponse(viewOf(ResourceKind.SPACE), viewOf(ResourceKind.PROJECT));
    }

    private CreationPolicyResponse.Entry viewOf(ResourceKind kind) {
        return policies.findById(kind)
                .map(p -> new CreationPolicyResponse.Entry(p.getMode(), List.copyOf(p.getTeamIds()),
                        p.getUpdatedBy(), p.getUpdatedAt()))
                .orElseGet(() -> new CreationPolicyResponse.Entry(CreationMode.ALL_ACTIVE, List.of(), null, null));
    }

    private static void requireCreatable(ResourceKind kind) {
        if (kind != ResourceKind.SPACE && kind != ResourceKind.PROJECT) {
            throw new IllegalArgumentException("생성 대상은 SPACE 또는 PROJECT입니다");
        }
    }
}
