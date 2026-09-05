package com.platform.orgservice.member;

import com.platform.common.error.ConflictException;
import com.platform.common.error.ForbiddenException;
import com.platform.common.error.NotFoundException;
import com.platform.orgservice.common.PageResponse;
import com.platform.orgservice.domain.GrantAudit;
import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.Member;
import com.platform.orgservice.domain.MemberEventType;
import com.platform.orgservice.domain.MemberKind;
import com.platform.orgservice.domain.MemberStatus;
import com.platform.orgservice.domain.PermAction;
import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.domain.SubjectType;
import com.platform.orgservice.domain.Team;
import com.platform.orgservice.domain.TeamMember;
import com.platform.orgservice.domain.TeamRole;
import com.platform.orgservice.keycloak.KeycloakAdminClient;
import com.platform.orgservice.member.dto.MemberApproveRequest;
import com.platform.orgservice.member.dto.MemberDetailResponse;
import com.platform.orgservice.member.dto.MemberEventResponse;
import com.platform.orgservice.member.dto.MemberPatchRequest;
import com.platform.orgservice.member.dto.MemberResponse;
import com.platform.orgservice.permission.PermissionFacade;
import com.platform.orgservice.profile.AvatarDirectory;
import com.platform.orgservice.repository.GrantAuditRepository;
import com.platform.orgservice.repository.GrantEntryRepository;
import com.platform.orgservice.repository.MemberEventRepository;
import com.platform.orgservice.repository.MemberRepository;
import com.platform.orgservice.repository.TeamMemberRepository;
import com.platform.orgservice.repository.TeamRepository;
import com.platform.orgservice.team.EveryoneTeamService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 사용자 목록·상세·상태 전이·승인.
 *
 * <p>상태 전이의 규칙은 두 가지다. 하나, <b>마지막 전역 관리자는 내릴 수 없다</b> — 아무도 권한을
 * 되돌릴 수 없는 상태를 만드는 조작은 실패해야 한다. 둘, <b>DEACTIVATED는 Keycloak 계정을 함께 잠근다</b> —
 * 우리 쪽 판정만 막으면 계정 자체는 열려 있다. Keycloak 호출 실패는 우리 상태를 되돌리지 않고
 * {@code member_event}에 남긴다(관리자가 퇴사 처리를 못 하게 되는 편이 더 나쁘다).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class MemberAdminService {

    public static final int MAX_PAGE_SIZE = 200;
    public static final int EVENT_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final MemberRepository members;
    private final MemberEventRepository memberEvents;
    private final TeamRepository teams;
    private final TeamMemberRepository teamMembers;
    private final GrantEntryRepository grants;
    private final GrantAuditRepository audits;
    private final PermissionFacade permissions;
    private final EveryoneTeamService everyone;
    private final MemberEventRecorder events;
    private final KeycloakAdminClient keycloak;
    private final AvatarDirectory avatars;

    // ---------------------------------------------------------------- 조회

    /**
     * 멤버 목록(배열). null 필터는 "거르지 않는다"는 뜻이고, 기본값(ACTIVE·HUMAN)은 컨트롤러가 정한다 —
     * 여기까지 내려오면 이미 결정된 조건이다.
     */
    @Transactional(readOnly = true)
    public List<MemberResponse> list(MemberStatus status, MemberKind kind, String q) {
        var avatarByMember = avatars.withAvatar();
        return members.findAll(specFor(status, kind, q)).stream()
                .map(m -> MemberResponse.from(m, avatarByMember.get(m.getId())))
                .toList();
    }

    /** 관리 화면용 페이지 응답. 필터 규칙은 목록과 같다. */
    @Transactional(readOnly = true)
    public PageResponse<MemberResponse> search(MemberStatus status, MemberKind kind, String q,
                                               int page, int size) {
        var avatarByMember = avatars.withAvatar();
        Page<Member> found = members.findAll(specFor(status, kind, q),
                PageRequest.of(Math.max(page, 0), clampSize(size)));
        return PageResponse.of(found, m -> MemberResponse.from(m, avatarByMember.get(m.getId())));
    }

    @Transactional(readOnly = true)
    public List<MemberResponse> pending(long actorId) {
        permissions.requireGlobalAdmin(actorId);
        var avatarByMember = avatars.withAvatar();
        return members.findByStatus(MemberStatus.PENDING).stream()
                .map(m -> MemberResponse.from(m, avatarByMember.get(m.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public MemberDetailResponse detail(long actorId, long memberId) {
        Member member = load(memberId);
        boolean privileged = actorId == memberId
                || permissions.check(actorId, ResourceKind.GLOBAL, "", PermAction.ADMIN).allowed();
        return MemberDetailResponse.of(member, teamViews(memberId),
                privileged ? grantViews(memberId) : null);
    }

    @Transactional(readOnly = true)
    public List<MemberEventResponse> events(long actorId, long memberId) {
        permissions.requireGlobalAdmin(actorId);
        if (!members.existsById(memberId)) throw new NotFoundException("멤버 없음: " + memberId);
        return memberEvents.findByMember(memberId, Limit.of(EVENT_PAGE_SIZE))
                .stream().map(MemberEventResponse::from).toList();
    }

    // ---------------------------------------------------------------- 변경

    public MemberDetailResponse patch(long actorId, long memberId, MemberPatchRequest req) {
        permissions.requireGlobalAdmin(actorId);
        Member member = load(memberId);
        // 이름은 여기서 못 바꾼다 — MemberMirrorFilter가 요청마다 JWT name으로 덮어쓴다(원천은 Keycloak).
        if (req.status() != null) changeStatus(actorId, member, req.status());
        return detail(actorId, memberId);
    }

    public MemberDetailResponse approve(long actorId, long memberId, MemberApproveRequest req) {
        permissions.requireGlobalAdmin(actorId);
        Member member = load(memberId);
        member.approve(actorId, Instant.now());
        everyone.add(member.getId());
        events.member(member.getId(), MemberEventType.APPROVED, actorId, "승인 대기 → 활성");

        for (MemberApproveRequest.TeamAssignment t : req.teamsOrEmpty()) {
            Team team = teams.findById(t.teamId())
                    .orElseThrow(() -> new NotFoundException("팀 없음: " + t.teamId()));
            if (team.isEveryone()) {
                throw new IllegalArgumentException("전체 구성원 팀은 자동으로 소속되므로 지정할 수 없습니다");
            }
            if (teamMembers.findByTeamIdAndMemberId(team.getId(), member.getId()).isEmpty()) {
                teamMembers.save(TeamMember.of(team.getId(), member.getId(),
                        t.role() == null ? TeamRole.MEMBER : t.role()));
                events.member(member.getId(), MemberEventType.TEAM_ADDED, actorId,
                        "승인과 함께 팀 합류: " + team.getName());
            }
        }
        for (MemberApproveRequest.GrantAssignment g : req.grantsOrEmpty()) {
            String resourceId = g.scope() == ResourceKind.GLOBAL || g.resourceId() == null ? "" : g.resourceId();
            Optional<GrantEntry> existing = grants.findBySubjectTypeAndSubjectIdAndResourceTypeAndResourceId(
                    SubjectType.USER, member.getId(), g.scope(), resourceId);
            if (existing.isPresent()) continue;
            GrantEntry saved = grants.save(GrantEntry.of(SubjectType.USER, member.getId(),
                    g.scope(), resourceId, g.role()));
            audits.save(GrantAudit.of(actorId, GrantAudit.Action.GRANTED, saved, member.getDisplayName()));
        }
        return detail(actorId, memberId);
    }

    private void changeStatus(long actorId, Member member, MemberStatus target) {
        if (member.getStatus() == target) return; // 멱등
        switch (target) {
            case ACTIVE -> activate(actorId, member);
            case SUSPENDED -> suspend(actorId, member);
            case DEACTIVATED -> deactivate(actorId, member);
            case PENDING -> throw new ConflictException("승인 대기 상태로 되돌릴 수 없습니다");
        }
    }

    private void activate(long actorId, Member member) {
        if (member.getStatus() == MemberStatus.DEACTIVATED) {
            // 퇴사자를 그냥 되살리면 Keycloak 계정·초대 이력이 어긋난 채로 활성이 된다.
            throw new ConflictException("비활성된 계정은 재초대로만 되돌릴 수 있습니다");
        }
        if (member.getStatus() == MemberStatus.PENDING) {
            member.approve(actorId, Instant.now());
            events.member(member.getId(), MemberEventType.APPROVED, actorId, "승인 대기 → 활성");
        } else {
            member.reactivate();
            events.member(member.getId(), MemberEventType.REACTIVATED, actorId, "정지 해제");
        }
        if (member.getKind() == MemberKind.HUMAN) everyone.add(member.getId());
    }

    private void suspend(long actorId, Member member) {
        requireNotLastGlobalAdmin(member, "마지막 전역 관리자는 내릴 수 없습니다");
        member.suspend(Instant.now());
        everyone.remove(member.getId());
        events.member(member.getId(), MemberEventType.SUSPENDED, actorId, "일시 정지");
    }

    private void deactivate(long actorId, Member member) {
        if (actorId == member.getId()) throw new ConflictException("자기 계정은 비활성화할 수 없습니다");
        requireNotLastGlobalAdmin(member, "마지막 전역 관리자는 내릴 수 없습니다");
        member.deactivate(Instant.now());
        everyone.remove(member.getId());
        events.member(member.getId(), MemberEventType.DEACTIVATED, actorId, "비활성(퇴사)");

        KeycloakAdminClient.Result result = keycloak.disableUserByEmail(member.getEmail());
        if (result == KeycloakAdminClient.Result.FAILED || result == KeycloakAdminClient.Result.USER_NOT_FOUND) {
            // 우리 상태는 이미 바뀌었다 — 다음 로그인은 어차피 차단된다. 흔적만 남긴다.
            events.member(member.getId(), MemberEventType.KEYCLOAK_DISABLED_FAILED, actorId,
                    "Keycloak 계정 비활성 실패: " + result.name());
        }
    }

    /**
     * 마지막 전역 관리자 보호. USER 직접 grant만 센다 — 팀 경유는 팀에서 사람이 빠지면 조용히 0이 되어
     * "마지막 한 명"의 근거가 되지 못한다.
     */
    private void requireNotLastGlobalAdmin(Member member, String message) {
        boolean isGlobalAdmin = grants.findBySubjectTypeAndSubjectIdAndResourceTypeAndResourceId(
                        SubjectType.USER, member.getId(), ResourceKind.GLOBAL, "")
                .filter(GrantEntry::isGlobalAdminOfUser)
                .isPresent();
        if (isGlobalAdmin && grants.countGlobalAdminUsers() <= 1) throw new ConflictException(message);
    }

    // ---------------------------------------------------------------- 보조

    private Member load(long memberId) {
        return members.findById(memberId).orElseThrow(() -> new NotFoundException("멤버 없음: " + memberId));
    }

    private List<MemberDetailResponse.TeamMembershipView> teamViews(long memberId) {
        List<TeamMember> memberships = teamMembers.findByMemberId(memberId);
        Map<Long, Team> byId = new LinkedHashMap<>();
        teams.findAllById(memberships.stream().map(TeamMember::getTeamId).toList())
                .forEach(t -> byId.put(t.getId(), t));
        List<MemberDetailResponse.TeamMembershipView> views = new ArrayList<>();
        for (TeamMember tm : memberships) {
            Team team = byId.get(tm.getTeamId());
            if (team == null) continue;
            views.add(new MemberDetailResponse.TeamMembershipView(team.getId(), team.getName(),
                    team.getKind().name(), tm.getRole().name()));
        }
        return views;
    }

    private List<MemberDetailResponse.GrantView> grantViews(long memberId) {
        return grants.findBySubjectTypeAndSubjectId(SubjectType.USER, memberId).stream()
                .map(g -> new MemberDetailResponse.GrantView(g.getId(), g.getResourceType().name(),
                        g.getResourceId(), g.getRole().name()))
                .toList();
    }

    /** {@code :x is null or ...}를 피해 Criteria로 조립한다(H2 enum 바인딩 회피). */
    private Specification<Member> specFor(MemberStatus status, MemberKind kind, String q) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (status != null) where.add(cb.equal(root.get("status"), status));
            if (kind != null) where.add(cb.equal(root.get("kind"), kind));
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
                where.add(cb.or(
                        cb.like(cb.lower(root.get("displayName")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("email"), "")), like)));
            }
            if (query != null) query.orderBy(cb.asc(root.get("displayName")), cb.asc(root.get("id")));
            return where.isEmpty() ? cb.conjunction() : cb.and(where.toArray(Predicate[]::new));
        };
    }

    private static int clampSize(int size) {
        if (size <= 0) return DEFAULT_PAGE_SIZE;
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
