package com.platform.orgservice.invitation;

import com.platform.common.error.ConflictException;
import com.platform.common.error.ForbiddenException;
import com.platform.common.error.NotFoundException;
import com.platform.orgservice.common.PageResponse;
import com.platform.orgservice.domain.AcceptedVia;
import com.platform.orgservice.domain.GrantAudit;
import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.Invitation;
import com.platform.orgservice.domain.InvitationGrant;
import com.platform.orgservice.domain.InvitationStatus;
import com.platform.orgservice.domain.InvitationTeam;
import com.platform.orgservice.domain.Member;
import com.platform.orgservice.domain.MemberEventType;
import com.platform.orgservice.domain.MemberKind;
import com.platform.orgservice.domain.PermAction;
import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.domain.SubjectType;
import com.platform.orgservice.domain.Team;
import com.platform.orgservice.domain.TeamMember;
import com.platform.orgservice.domain.TeamRole;
import com.platform.orgservice.invitation.dto.InvitationCreateRequest;
import com.platform.orgservice.invitation.dto.InvitationResponse;
import com.platform.orgservice.keycloak.KeycloakAdminClient;
import com.platform.orgservice.member.dto.MemberEventResponse;
import com.platform.orgservice.member.MemberEventRecorder;
import com.platform.orgservice.permission.PermissionFacade;
import com.platform.orgservice.repository.GrantAuditRepository;
import com.platform.orgservice.repository.GrantEntryRepository;
import com.platform.orgservice.repository.InvitationGrantRepository;
import com.platform.orgservice.repository.InvitationRepository;
import com.platform.orgservice.repository.InvitationTeamRepository;
import com.platform.orgservice.repository.MemberEventRepository;
import com.platform.orgservice.repository.MemberRepository;
import com.platform.orgservice.repository.TeamMemberRepository;
import com.platform.orgservice.repository.TeamRepository;
import com.platform.orgservice.team.EveryoneTeamService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 초대 원장과 그 소진.
 *
 * <p>핵심은 하나다: <b>매칭의 근거는 이메일</b>이고 토큰은 링크 검증·{@code login_hint}·추적용이다.
 * 로그인 전에는 그 사람의 id가 없어 팀·권한을 미리 줄 수 없기 때문이다. 그래서 초대 링크를 타지 않고
 * 그냥 구글로 로그인해도 초대는 소진된다.
 *
 * <p>다만 이메일을 신뢰할 수 없는 경로(Keycloak 비밀번호 가입, 이메일 검증 미적용)에서는
 * 링크(토큰) 경유만 인정한다 — 그러지 않으면 남의 이메일을 적어 남의 초대를 가로챌 수 있다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class InvitationService {

    /** 목록·이력 상한. 훑어보는 화면이라 이보다 길면 끝까지 읽지 않는다. */
    public static final int MAX_PAGE_SIZE = 200;
    public static final int EVENT_PAGE_SIZE = 100;

    private final InvitationRepository invitations;
    private final InvitationTeamRepository invitationTeams;
    private final InvitationGrantRepository invitationGrants;
    private final MemberRepository members;
    private final MemberEventRepository memberEvents;
    private final TeamRepository teams;
    private final TeamMemberRepository teamMembers;
    private final GrantEntryRepository grants;
    private final GrantAuditRepository audits;
    private final PermissionFacade permissions;
    private final EveryoneTeamService everyone;
    private final MemberEventRecorder events;
    private final InvitationMailer mailer;
    private final KeycloakAdminClient keycloak;

    @Value("${platform.org.invitation.ttl:P7D}")
    private Duration ttl;

    /** 초대 링크의 호스트. 링크 경로는 auth-server의 {@code /invite/{token}}이다. */
    @Value("${platform.org.invitation.base-url:}")
    private String baseUrl;

    /**
     * 토큰 없이(이메일 대조만으로) 초대를 소진해도 되는 로그인 경로.
     * 기본 GOOGLE — Keycloak 구글 IdP는 {@code trustEmail}이라 이메일이 검증된 값이다.
     */
    @Value("${platform.org.invitation.email-match-providers:GOOGLE}")
    private String emailMatchProviders;

    /** 리소스(SPACE/PROJECT) ADMIN의 초대 허용 여부. 조직 정책상 전역 관리자만이면 끈다. */
    @Value("${platform.org.invitation.resource-admin-enabled:true}")
    private boolean resourceAdminInviteEnabled;

    // ---------------------------------------------------------------- 생성·관리

    public List<InvitationResponse> create(long actorId, InvitationCreateRequest req) {
        List<InvitationCreateRequest.TeamPreset> teamPresets = req.teamsOrEmpty();
        List<InvitationCreateRequest.GrantPreset> grantPresets = req.grantsOrEmpty();
        requireCanInvite(actorId, teamPresets, grantPresets);
        validatePresetTargets(teamPresets);

        Instant now = Instant.now();
        String inviterName = displayNameOf(actorId);
        List<InvitationResponse> created = new ArrayList<>();
        for (String rawEmail : distinctEmails(req.emails())) {
            created.add(createOne(actorId, inviterName, rawEmail, req.message(), teamPresets, grantPresets, now));
        }
        return created;
    }

    private InvitationResponse createOne(long actorId, String inviterName, String rawEmail, String message,
                                         List<InvitationCreateRequest.TeamPreset> teamPresets,
                                         List<InvitationCreateRequest.GrantPreset> grantPresets,
                                         Instant now) {
        String email = rawEmail.trim();
        String norm = Invitation.normalize(email);
        rejectIfAlreadyUsable(email, norm);

        // 살아 있는 초대는 하나뿐이어야 한다 — 둘이 남으면 어느 프리셋이 적용됐는지 설명할 수 없다.
        invitations.findAllPendingByEmailNorm(norm).forEach(Invitation::expire);
        invitations.flush();

        String token = InvitationTokens.newToken();
        Invitation invitation = invitations.saveAndFlush(
                Invitation.of(email, InvitationTokens.hash(token), actorId, blankToNull(message),
                        now.plus(ttl)));

        for (InvitationCreateRequest.TeamPreset t : teamPresets) {
            invitationTeams.save(InvitationTeam.of(invitation.getId(), t.teamId(), t.role()));
        }
        for (InvitationCreateRequest.GrantPreset g : grantPresets) {
            invitationGrants.save(InvitationGrant.of(invitation.getId(), g.scope(), g.resourceId(), g.role()));
        }
        events.invitation(invitation.getId(), null, MemberEventType.INVITED, actorId, "초대 발송: " + email);

        String inviteUrl = inviteUrl(token);
        boolean mailSent = mailer.send(email, inviterName, message, teamNames(teamPresets), inviteUrl,
                invitation.getExpiresAt());
        return view(invitation, inviteUrl, mailSent);
    }

    /**
     * 이미 우리 계정인 이메일 처리.
     *
     * <p>ACTIVE·SUSPENDED에게 초대를 보내면 아무 일도 일어나지 않는다(초대는 PENDING 계정만 소진한다) —
     * 조용히 쌓이는 대신 여기서 막고 이유를 말한다. DEACTIVATED(퇴사)는 재초대가 유일한 복귀 경로이므로
     * 허용하고, 잠가 둔 Keycloak 계정을 다시 연다.
     */
    private void rejectIfAlreadyUsable(String email, String norm) {
        for (Member existing : members.findByEmailNorm(norm)) {
            switch (existing.getStatus()) {
                case ACTIVE -> throw new ConflictException("이미 활성 사용자입니다: " + email);
                case SUSPENDED -> throw new ConflictException("정지된 사용자입니다 — 정지를 해제하세요: " + email);
                case DEACTIVATED -> reopenForReinvite(existing);
                case PENDING -> { /* 승인 대기 중 — 초대가 도착하면 자동 승인된다 */ }
            }
        }
    }

    private void reopenForReinvite(Member member) {
        member.reopenForInvitation();
        KeycloakAdminClient.Result result = keycloak.enableUserByEmail(member.getEmail());
        if (result == KeycloakAdminClient.Result.FAILED) {
            events.member(member.getId(), MemberEventType.KEYCLOAK_DISABLED_FAILED, null,
                    "재초대 시 Keycloak 계정 활성화 실패");
        }
        events.member(member.getId(), MemberEventType.REACTIVATED, null, "재초대로 승인 대기 상태 복귀");
    }

    @Transactional(readOnly = true)
    public PageResponse<InvitationResponse> list(long actorId, InvitationStatus status, String q,
                                                 int page, int size) {
        Long invitedByFilter = permissions.check(actorId, ResourceKind.GLOBAL, "", PermAction.ADMIN).allowed()
                ? null
                : actorId; // 리소스 ADMIN은 자기가 보낸 것만 본다
        if (invitedByFilter != null) requireResourceAdminSomewhere(actorId);

        Specification<Invitation> spec = specFor(status, q, invitedByFilter);
        Page<Invitation> found = invitations.findAll(spec,
                PageRequest.of(Math.max(page, 0), clampSize(size)));

        Map<Long, List<InvitationResponse.TeamPresetView>> teamsByInvitation = teamViews(found.getContent());
        Map<Long, List<InvitationResponse.GrantPresetView>> grantsByInvitation = grantViews(found.getContent());
        Map<Long, String> inviterNames = displayNames(found.getContent().stream()
                .map(Invitation::getInvitedBy).toList());

        return PageResponse.of(found, i -> InvitationResponse.of(i, inviterNames.get(i.getInvitedBy()),
                teamsByInvitation.getOrDefault(i.getId(), List.of()),
                grantsByInvitation.getOrDefault(i.getId(), List.of()),
                null,   // 토큰 원문을 저장하지 않으므로 목록에서는 링크를 되살릴 수 없다
                null));
    }

    /** 새 토큰·새 만료. 이전 토큰은 이 순간 무효가 된다 — 재발송이 곧 링크 재발급이다. */
    public InvitationResponse resend(long actorId, long invitationId, boolean sendMail) {
        Invitation invitation = load(invitationId);
        requireCanManage(actorId, invitation);
        if (invitation.getStatus() == InvitationStatus.ACCEPTED) {
            throw new ConflictException("이미 수락된 초대입니다");
        }
        String token = InvitationTokens.newToken();
        invitation.reissue(InvitationTokens.hash(token), Instant.now().plus(ttl));
        events.invitation(invitation.getId(), null, MemberEventType.INVITE_RESENT, actorId, "초대 재발송");

        String inviteUrl = inviteUrl(token);
        boolean mailSent = sendMail && mailer.send(invitation.getEmail(), displayNameOf(actorId),
                invitation.getMessage(), teamNamesOf(invitation.getId()), inviteUrl, invitation.getExpiresAt());
        return view(invitation, inviteUrl, mailSent);
    }

    public void revoke(long actorId, long invitationId) {
        Invitation invitation = load(invitationId);
        requireCanManage(actorId, invitation);
        if (!invitation.isPending()) throw new ConflictException("대기 중인 초대만 철회할 수 있습니다");
        invitation.revoke();
        events.invitation(invitation.getId(), null, MemberEventType.INVITE_REVOKED, actorId, "초대 철회");
    }

    @Transactional(readOnly = true)
    public List<MemberEventResponse> events(long actorId, long invitationId) {
        permissions.requireGlobalAdmin(actorId);
        if (!invitations.existsById(invitationId)) throw new NotFoundException("초대 없음: " + invitationId);
        return memberEvents.findByInvitation(invitationId, Limit.of(EVENT_PAGE_SIZE))
                .stream().map(MemberEventResponse::from).toList();
    }

    // ---------------------------------------------------------------- 소진

    /**
     * 로그인 순간의 이메일 대조 소진. {@code provider}가 신뢰 목록에 없으면 초대가 있어도
     * 소진하지 않는다 — 그 사람은 승인 대기로 남고, 초대 링크를 타면 그때 TOKEN으로 소진된다.
     *
     * @return 소진해서 활성화됐는가
     */
    public boolean consumeOnLogin(Member member, String provider) {
        if (member.getKind() == MemberKind.AGENT) return false;
        Optional<Invitation> found = livePendingFor(member.getEmail());
        if (found.isEmpty()) return false;
        Invitation invitation = found.get();
        if (!emailMatchAllowed(provider)) return false;
        apply(invitation, member, AcceptedVia.EMAIL_MATCH);
        return true;
    }

    /**
     * 초대 링크 경유 수락(auth-server 내부 호출). 이메일이 초대와 다르면 거절한다 —
     * 링크를 얻은 사람이 다른 계정으로 로그인해 남의 프리셋을 가져가지 못하게 한다.
     */
    public AcceptOutcome acceptByToken(String token, long memberId, String email, String displayName) {
        Invitation invitation = invitations.findByTokenHash(InvitationTokens.hash(token)).orElse(null);
        if (invitation == null) return AcceptOutcome.notFound();
        if (!invitation.isPending()) return AcceptOutcome.rejected(invitation.getStatus().name());
        if (invitation.isExpiredAt(Instant.now())) {
            invitation.expire();
            events.invitation(invitation.getId(), null, MemberEventType.INVITE_EXPIRED, null, "만료된 링크 사용");
            return AcceptOutcome.rejected(InvitationStatus.EXPIRED.name());
        }
        if (!invitation.getEmailNorm().equals(Invitation.normalize(email))) {
            return AcceptOutcome.rejected("EMAIL_MISMATCH");
        }
        Member member = members.findById(memberId).orElseGet(() ->
                // 로그인 직후 호출이라 아직 org REST를 한 번도 거치지 않았을 수 있다(미러 행이 없다).
                members.saveAndFlush(Member.joining(memberId,
                        displayName == null || displayName.isBlank() ? email : displayName, email)));
        apply(invitation, member, AcceptedVia.TOKEN);
        return AcceptOutcome.accepted(invitation.getId());
    }

    /** 토큰 유효성만 — auth-server가 로그인 화면으로 보내기 전에 확인한다. */
    @Transactional(readOnly = true)
    public Optional<Invitation> findLiveByToken(String token) {
        return invitations.findByTokenHash(InvitationTokens.hash(token))
                .filter(Invitation::isPending)
                .filter(i -> !i.isExpiredAt(Instant.now()));
    }

    /** 이 사람에게 살아 있는 초대(만료된 것은 여기서 눕힌다). */
    private Optional<Invitation> livePendingFor(String email) {
        String norm = Invitation.normalize(email);
        if (norm.isEmpty()) return Optional.empty();
        List<Invitation> pending = invitations.findPendingByEmailNorm(norm, PageRequest.of(0, 1));
        if (pending.isEmpty()) return Optional.empty();
        Invitation invitation = pending.get(0);
        if (invitation.isExpiredAt(Instant.now())) {
            invitation.expire();
            events.invitation(invitation.getId(), null, MemberEventType.INVITE_EXPIRED, null, "만료");
            return Optional.empty();
        }
        return Optional.of(invitation);
    }

    /** 수락 처리 — 상태·팀·권한·전체 구성원·이력을 한 트랜잭션에서 맞춘다. */
    private void apply(Invitation invitation, Member member, AcceptedVia via) {
        Instant now = Instant.now();
        member.acceptInvitation();
        invitation.accept(member.getId(), via, now);

        for (InvitationTeam preset : invitationTeams.findByInvitationId(invitation.getId())) {
            Team team = teams.findById(preset.getTeamId()).orElse(null);
            if (team == null || team.isEveryone()) continue; // 사라진 팀·전체 구성원은 건너뛴다
            if (teamMembers.findByTeamIdAndMemberId(team.getId(), member.getId()).isEmpty()) {
                teamMembers.save(TeamMember.of(team.getId(), member.getId(), preset.getRole()));
                events.member(member.getId(), MemberEventType.TEAM_ADDED, invitation.getInvitedBy(),
                        "초대 프리셋으로 팀 합류: " + team.getName());
            }
        }
        for (InvitationGrant preset : invitationGrants.findByInvitationId(invitation.getId())) {
            upsertGrant(invitation.getInvitedBy(), member, preset);
        }
        everyone.add(member.getId());
        events.invitation(invitation.getId(), member.getId(), MemberEventType.JOINED,
                invitation.getInvitedBy(), "초대 수락(" + via.name() + ")");
    }

    /** 이미 같은 리소스에 grant가 있으면 더 높은 역할만 남긴다 — 초대가 기존 권한을 낮추지 않는다. */
    private void upsertGrant(long actorId, Member member, InvitationGrant preset) {
        String resourceId = preset.getResourceId() == null ? "" : preset.getResourceId();
        Optional<GrantEntry> existing = grants.findBySubjectTypeAndSubjectIdAndResourceTypeAndResourceId(
                SubjectType.USER, member.getId(), preset.getScope(), resourceId);
        if (existing.isPresent()) {
            GrantEntry entry = existing.get();
            if (entry.getRole().rank() < preset.getRole().rank()) {
                entry.changeRole(preset.getRole(), actorId, Instant.now());
                audits.save(GrantAudit.of(actorId, GrantAudit.Action.CHANGED, entry, member.getDisplayName()));
            }
            return;
        }
        GrantEntry saved = grants.save(GrantEntry.of(SubjectType.USER, member.getId(),
                preset.getScope(), resourceId, preset.getRole()));
        audits.save(GrantAudit.of(actorId, GrantAudit.Action.GRANTED, saved, member.getDisplayName()));
    }

    // ---------------------------------------------------------------- 만료

    /** 스케줄러가 부른다. 만료는 되돌리지 않는다 — 다시 필요하면 재발송이 새 토큰을 만든다. */
    public int expireDue(Instant now) {
        List<Invitation> due = invitations.findExpirable(now);
        due.forEach(i -> {
            i.expire();
            events.invitation(i.getId(), null, MemberEventType.INVITE_EXPIRED, null, "만료 스케줄러");
        });
        return due.size();
    }

    // ---------------------------------------------------------------- 권한

    private void requireCanInvite(long actorId,
                                  List<InvitationCreateRequest.TeamPreset> teamPresets,
                                  List<InvitationCreateRequest.GrantPreset> grantPresets) {
        if (permissions.check(actorId, ResourceKind.GLOBAL, "", PermAction.ADMIN).allowed()) return;
        if (!resourceAdminInviteEnabled) throw new ForbiddenException("초대 권한이 없습니다");

        for (InvitationCreateRequest.GrantPreset g : grantPresets) {
            if (g.scope() == ResourceKind.GLOBAL) {
                throw new ForbiddenException("전역 역할은 전역 관리자만 부여할 수 있습니다");
            }
            if (!permissions.check(actorId, g.scope(), g.resourceId(), PermAction.ADMIN).allowed()) {
                throw new ForbiddenException("권한을 관리할 수 없는 리소스가 프리셋에 있습니다");
            }
        }
        for (InvitationCreateRequest.TeamPreset t : teamPresets) {
            if (!isLead(actorId, t.teamId())) {
                throw new ForbiddenException("리더가 아닌 팀으로는 초대할 수 없습니다");
            }
        }
        requireResourceAdminSomewhere(actorId);
    }

    private void requireResourceAdminSomewhere(long actorId) {
        boolean any = permissions.grantsOf(actorId, null).stream()
                .anyMatch(g -> g.getRole() == com.platform.orgservice.domain.GrantRole.ADMIN);
        if (!any) throw new ForbiddenException("초대 권한이 없습니다");
    }

    private void requireCanManage(long actorId, Invitation invitation) {
        if (permissions.check(actorId, ResourceKind.GLOBAL, "", PermAction.ADMIN).allowed()) return;
        if (!resourceAdminInviteEnabled || !invitation.getInvitedBy().equals(actorId)) {
            throw new ForbiddenException("이 초대를 관리할 수 없습니다");
        }
        requireResourceAdminSomewhere(actorId);
    }

    private boolean isLead(long actorId, Long teamId) {
        return teamMembers.findByTeamIdAndMemberId(teamId, actorId)
                .map(tm -> tm.getRole() == TeamRole.LEAD)
                .orElse(false);
    }

    private boolean emailMatchAllowed(String provider) {
        if (provider == null || provider.isBlank()) return false;
        return trustedProviders().contains(provider.trim().toUpperCase(Locale.ROOT));
    }

    private Set<String> trustedProviders() {
        return Arrays.stream(emailMatchProviders.split(","))
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    // ---------------------------------------------------------------- 보조

    private void validatePresetTargets(List<InvitationCreateRequest.TeamPreset> teamPresets) {
        for (InvitationCreateRequest.TeamPreset t : teamPresets) {
            Team team = teams.findById(t.teamId())
                    .orElseThrow(() -> new NotFoundException("팀 없음: " + t.teamId()));
            if (team.isEveryone()) {
                throw new IllegalArgumentException("전체 구성원 팀은 자동으로 소속되므로 지정할 수 없습니다");
            }
        }
    }

    /** 붙여넣기로 들어온 목록 — 공백·중복·형식을 여기서 한 번에 정리한다. */
    private List<String> distinctEmails(List<String> raw) {
        Map<String, String> byNorm = new LinkedHashMap<>();
        for (String value : raw) {
            if (value == null || value.isBlank()) continue;
            String email = value.trim();
            int at = email.indexOf('@');
            if (at <= 0 || at == email.length() - 1 || email.length() > 320) {
                throw new IllegalArgumentException("이메일 형식이 올바르지 않습니다: " + email);
            }
            byNorm.putIfAbsent(Invitation.normalize(email), email);
        }
        if (byNorm.isEmpty()) throw new IllegalArgumentException("초대할 이메일을 입력하세요");
        return List.copyOf(byNorm.values());
    }

    private Specification<Invitation> specFor(InvitationStatus status, String q, Long invitedBy) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (status != null) where.add(cb.equal(root.get("status"), status));
            if (q != null && !q.isBlank()) {
                where.add(cb.like(root.get("emailNorm"), "%" + Invitation.normalize(q) + "%"));
            }
            if (invitedBy != null) where.add(cb.equal(root.get("invitedBy"), invitedBy));
            if (query != null) query.orderBy(cb.desc(root.get("createdAt")), cb.desc(root.get("id")));
            return where.isEmpty() ? cb.conjunction() : cb.and(where.toArray(Predicate[]::new));
        };
    }

    private Invitation load(long id) {
        return invitations.findById(id).orElseThrow(() -> new NotFoundException("초대 없음: " + id));
    }

    private InvitationResponse view(Invitation invitation, String inviteUrl, Boolean mailSent) {
        return InvitationResponse.of(invitation, displayNameOf(invitation.getInvitedBy()),
                teamViews(List.of(invitation)).getOrDefault(invitation.getId(), List.of()),
                grantViews(List.of(invitation)).getOrDefault(invitation.getId(), List.of()),
                inviteUrl, mailSent);
    }

    private Map<Long, List<InvitationResponse.TeamPresetView>> teamViews(List<Invitation> found) {
        if (found.isEmpty()) return Map.of();
        List<Long> ids = found.stream().map(Invitation::getId).toList();
        Map<Long, String> teamNames = new LinkedHashMap<>();
        teams.findAllById(invitationTeams.findByInvitationIdIn(ids).stream()
                .map(InvitationTeam::getTeamId).distinct().toList())
                .forEach(t -> teamNames.put(t.getId(), t.getName()));
        Map<Long, List<InvitationResponse.TeamPresetView>> byInvitation = new LinkedHashMap<>();
        for (InvitationTeam it : invitationTeams.findByInvitationIdIn(ids)) {
            byInvitation.computeIfAbsent(it.getInvitationId(), k -> new ArrayList<>())
                    .add(new InvitationResponse.TeamPresetView(it.getTeamId(),
                            teamNames.getOrDefault(it.getTeamId(), "팀 #" + it.getTeamId()),
                            it.getRole().name()));
        }
        return byInvitation;
    }

    private Map<Long, List<InvitationResponse.GrantPresetView>> grantViews(List<Invitation> found) {
        if (found.isEmpty()) return Map.of();
        List<Long> ids = found.stream().map(Invitation::getId).toList();
        Map<Long, List<InvitationResponse.GrantPresetView>> byInvitation = new LinkedHashMap<>();
        for (InvitationGrant g : invitationGrants.findByInvitationIdIn(ids)) {
            byInvitation.computeIfAbsent(g.getInvitationId(), k -> new ArrayList<>())
                    .add(new InvitationResponse.GrantPresetView(g.getScope().name(), g.getResourceId(),
                            g.getRole().name()));
        }
        return byInvitation;
    }

    private List<String> teamNames(List<InvitationCreateRequest.TeamPreset> presets) {
        return teams.findAllById(presets.stream().map(InvitationCreateRequest.TeamPreset::teamId).toList())
                .stream().map(Team::getName).toList();
    }

    private List<String> teamNamesOf(long invitationId) {
        return teams.findAllById(invitationTeams.findByInvitationId(invitationId).stream()
                .map(InvitationTeam::getTeamId).toList()).stream().map(Team::getName).toList();
    }

    private Map<Long, String> displayNames(List<Long> memberIds) {
        Map<Long, String> names = new LinkedHashMap<>();
        members.findAllById(memberIds.stream().distinct().toList())
                .forEach(m -> names.put(m.getId(), m.getDisplayName()));
        return names;
    }

    private String displayNameOf(long memberId) {
        return members.findById(memberId).map(Member::getDisplayName).orElse("사용자 #" + memberId);
    }

    /** 초대 링크. base-url이 없으면 상대 경로로 준다 — 화면이 자기 오리진을 붙여 복사한다. */
    private String inviteUrl(String token) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/invite/" + token;
    }

    private static int clampSize(int size) {
        if (size <= 0) return 20;
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 수락 시도의 결과. 실패 이유를 상태 문자열로 돌려줘 auth-server가 로그만 남기고 로그인은 계속한다. */
    public record AcceptOutcome(boolean accepted, String status, Long invitationId) {
        static AcceptOutcome accepted(Long id) { return new AcceptOutcome(true, "ACCEPTED", id); }
        static AcceptOutcome rejected(String status) { return new AcceptOutcome(false, status, null); }
        static AcceptOutcome notFound() { return new AcceptOutcome(false, "NOT_FOUND", null); }
    }

    /** 멤버 상태가 ACTIVE인지와 무관하게 초대가 살아 있는지만 본다(승인 대기 화면이 쓴다). */
    @Transactional(readOnly = true)
    public boolean hasLiveInvitation(String email) {
        String norm = Invitation.normalize(email);
        return !norm.isEmpty() && !invitations.findPendingByEmailNorm(norm, PageRequest.of(0, 1)).isEmpty();
    }

    /** 승인 대기 목록 화면이 상태 필터를 문자열로 받는다 — 잘못된 값은 400으로 돌려준다. */
    public static InvitationStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return InvitationStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("알 수 없는 초대 상태입니다: " + raw);
        }
    }
}
