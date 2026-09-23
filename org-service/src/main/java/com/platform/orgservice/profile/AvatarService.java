package com.platform.orgservice.profile;

import com.platform.common.error.NotFoundException;
import com.platform.orgservice.domain.MemberEventType;
import com.platform.orgservice.member.MemberEventRecorder;
import com.platform.orgservice.permission.PermissionFacade;
import com.platform.orgservice.profile.dto.AvatarView;
import com.platform.orgservice.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * 멤버 아바타(플랫폼 공통). 바이트는 S3 호환 저장소(또는 로컬 파일)에 두고 member_profile 행에는
 * 키만 남긴다. 이 서비스가 org에 있는 이유는 아바타를 ALM·위키·보드가 함께 보기 때문이다 —
 * 사용자 디렉터리(GET /api/org/members)가 이미 여기에 있고, 아바타만 다른 서비스에 두면
 * 목록 화면마다 두 서비스를 합쳐야 한다.
 *
 * 형식은 클라이언트가 보낸 Content-Type이 아니라 매직 바이트로 판별한다: 스크립트를 실을 수 있는
 * SVG/HTML이 프로필 사진 이름으로 들어오면 그대로 인라인 표시된다.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class AvatarService {

    /** 프로필 사진 한 장에 필요한 크기 — 넘으면 400 */
    public static final long MAX_BYTES = 2L * 1024 * 1024;

    /** 판별된 타입 → 키 확장자. 허용 목록이자 확장자 사전이다 */
    private static final Map<String, String> ALLOWED = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/webp", "webp");

    private final MemberRepository members;
    private final MemberProfileRepository profiles;
    private final AvatarStorage storage;
    private final PermissionFacade permissions;
    private final MemberEventRecorder events;

    public record AvatarImage(Resource resource, String contentType) {}

    public AvatarView upload(long memberId, MultipartFile file) {
        if (!members.existsById(memberId)) throw new NotFoundException("멤버를 찾을 수 없습니다");
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("빈 파일은 올릴 수 없습니다");
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("아바타는 2MB 이하 이미지여야 합니다");
        }
        String contentType;
        try (InputStream probe = file.getInputStream()) {
            contentType = AvatarMediaTypes.detect(probe);
        } catch (IOException e) {
            throw new UncheckedIOException("업로드 스트림 읽기 실패", e);
        }
        String extension = ALLOWED.get(contentType);
        if (extension == null) {
            throw new IllegalArgumentException("아바타는 PNG·JPG·WebP 이미지만 올릴 수 있습니다");
        }

        String key = "avatars/" + memberId + "/" + UUID.randomUUID() + "." + extension;
        try (InputStream input = file.getInputStream()) {
            storage.store(input, file.getSize(), contentType, key);
        } catch (IOException e) {
            throw new UncheckedIOException("업로드 스트림 읽기 실패", e);
        }
        // 메타가 롤백되면 바이트만 남는다 — 롤백 시 방금 올린 오브젝트를 되돌린다
        deleteAfter(false, key);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        MemberProfile profile = ensureRow(memberId, now);
        String previous = profile.attachAvatar(key, contentType, now);
        // 이전 사진은 커밋이 확정된 뒤에 지운다 — 롤백된 교체가 쓰던 사진을 날리지 않게
        if (previous != null && !previous.equals(key)) deleteAfter(true, previous);
        return AvatarView.from(profile);
    }

    /** @return 실제로 지운 아바타가 있었는지 — 없던 아바타를 지운 것은 이력에 남길 일이 아니다 */
    public boolean remove(long memberId) {
        return profiles.findById(memberId).map(profile -> {
            String previous = profile.clearAvatar(Instant.now().truncatedTo(ChronoUnit.MICROS));
            if (previous == null) return false;
            deleteAfter(true, previous);
            return true;
        }).orElse(false);
    }

    /**
     * 남의 아바타를 올린다 — 전역 관리자만. 검증·저장·이전 오브젝트 정리는 본인 경로와 같은 규칙을 탄다.
     *
     * <p>이 경로가 필요한 이유는 AGENT 멤버다. 에이전트는 브라우저로 로그인하지 않으니 스스로
     * {@code /api/org/me/avatar}를 부를 수 없고, 관리자가 대신 넣어 주지 않으면 목록에서 영영 얼굴이 없다.
     */
    public AvatarView uploadFor(long actorId, long memberId, MultipartFile file) {
        permissions.requireGlobalAdmin(actorId);
        AvatarView view = upload(memberId, file);
        recordIfOnBehalf(actorId, memberId, MemberEventType.AVATAR_CHANGED, "관리자가 아바타를 올렸습니다");
        return view;
    }

    /** 남의 아바타를 지운다 — 전역 관리자만. 아바타가 없어도 204(멱등), 멤버 자체가 없으면 404. */
    public void removeFor(long actorId, long memberId) {
        permissions.requireGlobalAdmin(actorId);
        if (!members.existsById(memberId)) throw new NotFoundException("멤버를 찾을 수 없습니다");
        if (remove(memberId)) {
            recordIfOnBehalf(actorId, memberId, MemberEventType.AVATAR_REMOVED, "관리자가 아바타를 지웠습니다");
        }
    }

    /** 남의 얼굴을 바꾼 것만 이력에 남긴다 — 자기 사진을 관리자 경로로 바꾼 것은 본인 경로와 다르지 않다. */
    private void recordIfOnBehalf(long actorId, long memberId, MemberEventType type, String detail) {
        if (actorId != memberId) events.member(memberId, type, actorId, detail);
    }

    @Transactional(readOnly = true)
    public AvatarImage image(long memberId) {
        MemberProfile profile = profiles.findById(memberId)
                .filter(MemberProfile::hasAvatar)
                .orElseThrow(() -> new NotFoundException("아바타가 없습니다"));
        String contentType = profile.getAvatarContentType();
        return new AvatarImage(storage.open(storage.defaultBucket(), profile.getAvatarKey()),
                contentType == null || contentType.isBlank() ? AvatarMediaTypes.OCTET_STREAM : contentType);
    }

    /** 프로필 행은 첫 업로드 때 만든다 — 아바타를 올리지 않은 멤버에게 빈 행을 만들어 두지 않는다 */
    private MemberProfile ensureRow(long memberId, Instant at) {
        return profiles.findById(memberId).orElseGet(() -> profiles.save(MemberProfile.of(memberId, at)));
    }

    private void deleteAfter(boolean onCommit, String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            if (onCommit) removeQuietly(key);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if ((status == STATUS_COMMITTED) == onCommit) removeQuietly(key);
            }
        });
    }

    private void removeQuietly(String key) {
        if (!storage.delete(storage.defaultBucket(), key)) {
            // 고아 오브젝트를 거두는 정리 잡은 없다 — 저장소에 바이트가 남고 키만 로그로 남는다.
            // 손으로 지우려면 이 경고의 key를 쓴다.
            log.warn("아바타 오브젝트 삭제 실패(고아로 남음) key={}", key);
        }
    }
}
