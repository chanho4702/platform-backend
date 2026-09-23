package com.platform.orgservice.profile;

import com.platform.orgservice.profile.dto.AvatarView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 아바타. 자기 얼굴은 본인이 올리고 지우며({@code /me}), 보는 것은 로그인한 누구나다 —
 * ALM 담당자 셀, 위키 작성자, 코멘트에 같은 조직 사람들의 얼굴이 떠야 하기 때문이다.
 * 남의 얼굴을 올리고 지우는 {@code /members/{id}} 경로는 전역 관리자만 — 브라우저로 로그인하지 않는
 * AGENT 멤버의 얼굴은 이 경로로만 들어온다.
 */
@Tag(name = "Avatars", description = "아바타 이미지 — 본인 것은 본인이, 남의 것은 전역 관리자가. 보는 것은 로그인한 누구나.")
@RestController
@RequiredArgsConstructor
public class AvatarController {

    private final AvatarService avatars;

    @Operation(summary = "내 아바타 업로드 — multipart/form-data. 2MB를 넘으면 400")
    @PutMapping("/api/org/me/avatar")
    public AvatarView upload(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal Jwt jwt) {
        return avatars.upload(memberId(jwt), file);
    }

    @Operation(summary = "내 아바타 삭제")
    @DeleteMapping("/api/org/me/avatar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal Jwt jwt) {
        avatars.remove(memberId(jwt));
    }

    @Operation(summary = "멤버 아바타 업로드(관리자) — multipart/form-data. 전역 관리자만, 2MB를 넘으면 400")
    @PutMapping("/api/org/members/{memberId}/avatar")
    public AvatarView uploadFor(@Parameter(description = "대상 멤버 id") @PathVariable long memberId,
                                @RequestParam("file") MultipartFile file,
                                @AuthenticationPrincipal Jwt jwt) {
        return avatars.uploadFor(memberId(jwt), memberId, file);
    }

    @Operation(summary = "멤버 아바타 삭제(관리자) — 전역 관리자만")
    @DeleteMapping("/api/org/members/{memberId}/avatar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFor(@Parameter(description = "대상 멤버 id") @PathVariable long memberId,
                          @AuthenticationPrincipal Jwt jwt) {
        avatars.removeFor(memberId(jwt), memberId);
    }

    /**
     * 원본 타입 그대로 인라인. 짧게(5분) 사적 캐시를 허용하고 URL의 {@code ?v=}가 갱신을 밀어낸다 —
     * 목록 한 화면에 같은 사진이 수십 번 뜨는데 매번 받아올 이유가 없다.
     */
    @Operation(summary = "멤버 아바타 이미지 조회 — 원본 타입 그대로 인라인, 5분 사적 캐시")
    @GetMapping("/api/org/members/{memberId}/avatar")
    public ResponseEntity<Resource> image(@Parameter(description = "멤버 id") @PathVariable long memberId) {
        AvatarService.AvatarImage image = avatars.image(memberId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                .header("Cross-Origin-Resource-Policy", "same-origin")
                .contentType(MediaType.parseMediaType(image.contentType()))
                .body(image.resource());
    }

    /** sub는 auth-server user id = member PK. 숫자가 아닌 토큰은 애초에 미러링도 되지 않는다 */
    static long memberId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
