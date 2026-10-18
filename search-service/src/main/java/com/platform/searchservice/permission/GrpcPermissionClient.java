package com.platform.searchservice.permission;

import com.platform.proto.org.v1.Action;
import com.platform.proto.org.v1.CheckPermissionRequest;
import com.platform.proto.org.v1.CheckPermissionResponse;
import com.platform.proto.org.v1.ListUserGrantsRequest;
import com.platform.proto.org.v1.ListUserGrantsResponse;
import com.platform.proto.org.v1.PermissionServiceGrpc;
import com.platform.proto.org.v1.ResourceType;
import com.platform.common.error.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * org-service gRPC 권한 클라이언트.
 *
 * `org.proto`의 `ListUserGrants`에는 이미 "검색 결과 권한 필터용(Wave C)" 주석이 달려 있다 —
 * 신규 계약이 아니라 예약돼 있던 계약을 소비하는 것이다.
 *
 * 캐시를 두지 않는다: 검색은 wiki-backend의 페이지 조회처럼 초당 여러 번 오는 호출이 아니고,
 * 권한 회수가 검색 결과에 늦게 반영되는 쪽이 더 나쁘다. 부하가 실측되면 그때 짧은 TTL을 붙인다.
 *
 * <p>모든 호출에 데드라인을 건다(P-04, wiki·alm과 같은 {@code platform.org-grpc.deadline-seconds}).
 * 없으면 org가 행(hang)할 때 검색 요청 스레드가 무기한 묶여 톰캣 풀 전체가 굳는다.
 * {@code withDeadlineAfter}는 호출 시점 기준 절대 시각을 계산하므로 스텁을 미리 만들어 두지 않고 매 호출 새로 만든다.
 */
@Slf4j
public class GrpcPermissionClient implements PermissionClient {

    private final PermissionServiceGrpc.PermissionServiceBlockingStub stub;
    private final long deadlineSeconds;

    public GrpcPermissionClient(PermissionServiceGrpc.PermissionServiceBlockingStub stub, long deadlineSeconds) {
        if (deadlineSeconds <= 0) throw new IllegalArgumentException("deadlineSeconds must be positive");
        this.stub = stub;
        this.deadlineSeconds = deadlineSeconds;
    }

    private PermissionServiceGrpc.PermissionServiceBlockingStub deadline() {
        return stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS);
    }

    @Override
    public AccessScope accessibleSpaces(long userId) {
        try {
            ListUserGrantsResponse res = deadline().listUserGrants(
                    ListUserGrantsRequest.newBuilder().setUserId(userId).build()); // UNSPECIFIED = 전체
            boolean global = res.getGrantsList().stream()
                    .anyMatch(g -> g.getResourceType() == ResourceType.GLOBAL);
            if (global) return AccessScope.global();

            Set<Long> ids = res.getGrantsList().stream()
                    .filter(g -> g.getResourceType() == ResourceType.SPACE)
                    .map(g -> parseSpaceId(g.getResourceId()))
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toSet());
            return AccessScope.of(ids);
        } catch (Exception e) {
            // 전송 장애든 아니든 검색은 503으로 끝낸다. wiki-backend는 비-가용성 오류를
            // fail-closed(빈 목록)로 삼켰지만, 검색에서 빈 목록은 "결과 없음"과 구분되지 않아
            // 사용자가 권한 문제인지 알 방법이 없다.
            log.error("권한 조회 실패 — 검색 503 전파: user={}", userId, e);
            throw new ServiceUnavailableException("권한 서비스에 연결할 수 없습니다");
        }
    }

    @Override
    public boolean isGlobalAdmin(long userId) {
        try {
            // org.proto가 이미 가진 계약 그대로다 — GLOBAL이면 resource_id는 빈 값으로 정규화된다.
            CheckPermissionResponse res = deadline().checkPermission(CheckPermissionRequest.newBuilder()
                    .setUserId(userId)
                    .setResourceType(ResourceType.GLOBAL)
                    .setResourceId("")
                    .setAction(Action.ADMIN)
                    .build());
            return res.getAllowed();
        } catch (Exception e) {
            log.error("전역 관리자 판정 실패 — 503 전파: user={}", userId, e);
            throw new ServiceUnavailableException("권한 서비스에 연결할 수 없습니다");
        }
    }

    /** resourceId는 문자열 계약이다 — 숫자가 아닌 값이 섞여도 검색 전체를 죽이지 않는다. */
    private static Long parseSpaceId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            log.warn("SPACE grant의 resourceId가 숫자가 아니다 — 건너뜀: {}", raw);
            return null;
        }
    }
}
