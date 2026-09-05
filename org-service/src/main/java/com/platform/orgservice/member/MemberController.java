package com.platform.orgservice.member;

import com.platform.orgservice.common.PageResponse;
import com.platform.orgservice.domain.MemberKind;
import com.platform.orgservice.domain.MemberStatus;
import com.platform.orgservice.member.dto.MemberApproveRequest;
import com.platform.orgservice.member.dto.MemberDetailResponse;
import com.platform.orgservice.member.dto.MemberEventResponse;
import com.platform.orgservice.member.dto.MemberPatchRequest;
import com.platform.orgservice.member.dto.MemberResponse;
import com.platform.orgservice.config.ConflictResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;

@Tag(name = "Members", description = "조직 멤버 — 로그인한 사람은 첫 요청에 자동으로 미러링되고, 이 API는 그 원장을 조회·승인·상태 전이한다.")
@RestController
@RequestMapping("/api/org/members")
@RequiredArgsConstructor
public class MemberController {

    /** 필터를 끄는 값. 기본이 ACTIVE·HUMAN이므로 "전부"를 원하면 명시해야 한다. */
    private static final String ALL = "ALL";

    private final MemberAdminService admin;

    /**
     * 멤버 목록 — <b>배열</b>이다(기존 계약).
     *
     * <p>ALM 13화면·위키 10화면이 이 배열을 직접 받는다. 페이지네이션이 필요한 관리 화면은
     * {@code /members/page}를 따로 쓴다 — 한 경로가 두 모양을 내면 어느 쪽이 계약인지 흐려진다.
     *
     * <p>기본 필터는 {@code status=ACTIVE&kind=HUMAN}이다. 소비자는 어차피 ACTIVE만 걸러 쓰고
     * AGENT는 아무도 거르지 않아 사람 목록에 페르소나가 섞여 보였다. 전부가 필요하면
     * {@code ?status=ALL&kind=ALL}.
     */
    @Operation(summary = "멤버 목록 조회 — 배열. 기본 필터는 status=ACTIVE, kind=HUMAN")
    @GetMapping
    public List<MemberResponse> list(@Parameter(description = "상태 필터 — ACTIVE | PENDING | SUSPENDED | DEACTIVATED, 또는 ALL(필터 없음). 미지정이면 ACTIVE.")
                                     @RequestParam(required = false) String status,
                                     @Parameter(description = "종류 필터 — HUMAN | AGENT, 또는 ALL(필터 없음). 미지정이면 HUMAN.")
                                     @RequestParam(required = false) String kind,
                                     @Parameter(description = "이름·이메일 부분 검색어")
                                     @RequestParam(required = false) String q) {
        return admin.list(statusFilter(status), kindFilter(kind), q);
    }

    /** 관리 화면용 페이지 응답. 필터 규칙은 목록과 같다. */
    @Operation(summary = "멤버 목록 페이지 조회 — 관리 화면용. 필터 규칙은 목록과 같다")
    @GetMapping("/page")
    public PageResponse<MemberResponse> page(@Parameter(description = "상태 필터 — ACTIVE | PENDING | SUSPENDED | DEACTIVATED, 또는 ALL. 미지정이면 ACTIVE.")
                                             @RequestParam(required = false) String status,
                                             @Parameter(description = "종류 필터 — HUMAN | AGENT, 또는 ALL. 미지정이면 HUMAN.")
                                             @RequestParam(required = false) String kind,
                                             @Parameter(description = "이름·이메일 부분 검색어")
                                             @RequestParam(required = false) String q,
                                             @Parameter(description = "0부터 세는 페이지 번호")
                                             @RequestParam(defaultValue = "0") int page,
                                             @Parameter(description = "페이지 크기")
                                             @RequestParam(defaultValue = "20") int size) {
        return admin.search(statusFilter(status), kindFilter(kind), q, page, size);
    }

    /** 승인 대기 목록. {@code /{id}}보다 먼저 선언해 "pending"이 id로 해석되지 않게 한다. */
    @Operation(summary = "승인 대기 멤버 목록 조회")
    @GetMapping("/pending")
    public List<MemberResponse> pending(@AuthenticationPrincipal Jwt jwt) {
        return admin.pending(userId(jwt));
    }

    @Operation(summary = "멤버 상세 조회 — grants는 전역 관리자와 본인에게만 채워진다")
    @GetMapping("/{id}")
    public MemberDetailResponse detail(@AuthenticationPrincipal Jwt jwt,
                                       @Parameter(description = "멤버 id (= auth-server user id)") @PathVariable Long id) {
        return admin.detail(userId(jwt), id);
    }

    /**
     * 상태 전이만 한다.
     *
     * <p>표시 이름은 여기서 못 바꾼다 — {@code MemberMirrorFilter}가 요청마다 JWT의 {@code name}으로
     * 덮어쓰므로 고쳐 봐야 다음 로그인에 되돌아간다. 이름은 Keycloak 프로필이 원천이다.
     */
    @Operation(summary = "멤버 상태 전이 — 표시 이름은 여기서 바꿀 수 없다")
    @ConflictResponse("허용되지 않는 상태 전이입니다 — 자기 계정 비활성화, 마지막 전역 관리자 정지 등.")
    @PatchMapping("/{id}")
    public MemberDetailResponse patch(@AuthenticationPrincipal Jwt jwt,
                                      @Parameter(description = "멤버 id") @PathVariable Long id,
                                      @Valid @RequestBody MemberPatchRequest req) {
        return admin.patch(userId(jwt), id, req);
    }

    @Operation(summary = "승인 대기 멤버 승인 — 팀·권한 부여는 선택")
    @ConflictResponse("승인 대기 중인 계정이 아닙니다.")
    @PostMapping("/{id}/approve")
    public MemberDetailResponse approve(@AuthenticationPrincipal Jwt jwt,
                                        @Parameter(description = "멤버 id") @PathVariable Long id,
                                        @RequestBody(required = false) MemberApproveRequest req) {
        return admin.approve(userId(jwt), id, req == null ? new MemberApproveRequest(null, null) : req);
    }

    @Operation(summary = "멤버의 초대·상태 이력 조회")
    @GetMapping("/{id}/events")
    public List<MemberEventResponse> events(@AuthenticationPrincipal Jwt jwt,
                                            @Parameter(description = "멤버 id") @PathVariable Long id) {
        return admin.events(userId(jwt), id);
    }

    /** 미지정이면 ACTIVE, {@code ALL}이면 필터 없음. */
    private static MemberStatus statusFilter(String raw) {
        if (raw == null || raw.isBlank()) return MemberStatus.ACTIVE;
        if (ALL.equalsIgnoreCase(raw.trim())) return null;
        try {
            return MemberStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("알 수 없는 사용자 상태입니다: " + raw);
        }
    }

    /** 미지정이면 HUMAN, {@code ALL}이면 필터 없음. */
    private static MemberKind kindFilter(String raw) {
        if (raw == null || raw.isBlank()) return MemberKind.HUMAN;
        if (ALL.equalsIgnoreCase(raw.trim())) return null;
        try {
            return MemberKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("알 수 없는 사용자 종류입니다: " + raw);
        }
    }

    private static long userId(Jwt jwt) { return Long.parseLong(jwt.getSubject()); }
}
