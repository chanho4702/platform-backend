package com.platform.orgservice.profile;

import com.platform.orgservice.profile.dto.AvatarView;
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
 * 아바타. 올리고 지우는 것은 본인만이고(경로에 멤버 id가 없다), 보는 것은 로그인한 누구나다 —
 * ALM 담당자 셀, 위키 작성자, 코멘트에 같은 조직 사람들의 얼굴이 떠야 하기 때문이다.
 */
@RestController
@RequiredArgsConstructor
public class AvatarController {

    private final AvatarService avatars;

    @PutMapping("/api/org/me/avatar")
    public AvatarView upload(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal Jwt jwt) {
        return avatars.upload(memberId(jwt), file);
    }

    @DeleteMapping("/api/org/me/avatar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal Jwt jwt) {
        avatars.remove(memberId(jwt));
    }

    /**
     * 원본 타입 그대로 인라인. 짧게(5분) 사적 캐시를 허용하고 URL의 {@code ?v=}가 갱신을 밀어낸다 —
     * 목록 한 화면에 같은 사진이 수십 번 뜨는데 매번 받아올 이유가 없다.
     */
    @GetMapping("/api/org/members/{memberId}/avatar")
    public ResponseEntity<Resource> image(@PathVariable long memberId) {
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
