package com.platform.searchservice.permission;

import com.platform.proto.org.v1.ListUserGrantsRequest;
import com.platform.proto.org.v1.ListUserGrantsResponse;
import com.platform.proto.org.v1.PermissionServiceGrpc;
import com.platform.proto.org.v1.ResourceType;
import com.platform.searchservice.common.ServiceUnavailableException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * org-service gRPC 권한 클라이언트.
 *
 * `org.proto`의 `ListUserGrants`에는 이미 "검색 결과 권한 필터용(Wave C)" 주석이 달려 있다 —
 * 신규 계약이 아니라 예약돼 있던 계약을 소비하는 것이다.
 *
 * 캐시를 두지 않는다: 검색은 wiki-backend의 페이지 조회처럼 초당 여러 번 오는 호출이 아니고,
 * 권한 회수가 검색 결과에 늦게 반영되는 쪽이 더 나쁘다. 부하가 실측되면 그때 짧은 TTL을 붙인다.
 */
@Slf4j
public class GrpcPermissionClient implements PermissionClient {

    private final PermissionServiceGrpc.PermissionServiceBlockingStub stub;

    public GrpcPermissionClient(PermissionServiceGrpc.PermissionServiceBlockingStub stub) {
        this.stub = stub;
    }

    @Override
    public AccessScope accessibleSpaces(long userId) {
        try {
            ListUserGrantsResponse res = stub.listUserGrants(
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

    /** resourceId는 문자열 계약이다 — 숫자가 아닌 값이 섞여도 검색 전체를 죽이지 않는다. */
    private static Long parseSpaceId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            log.warn("SPACE grant의 resourceId가 숫자가 아니다 — 건너뜀: {}", raw);
            return null;
        }
    }

    @SuppressWarnings("unused") // 진단용 — 전송 장애와 그 외를 로그에서 구분할 때 쓴다
    private static boolean isUnavailable(Throwable e) {
        if (e instanceof StatusRuntimeException sre) {
            Status.Code code = sre.getStatus().getCode();
            return code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED;
        }
        return false;
    }
}
