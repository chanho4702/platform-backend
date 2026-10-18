package com.platform.common.grpc;

import java.util.Locale;

/**
 * 서비스 간 gRPC 토큰 검사 모드(P-01). 단계적 롤아웃용이다:
 *
 * <ol>
 *   <li>{@link #MONITOR}(기본) — 서버는 토큰이 없거나 틀려도 통과시키고 경고 로그만 남긴다.
 *       모든 호출자가 토큰을 보내기 시작했는지 로그로 확인하는 단계.</li>
 *   <li>{@link #ENFORCE} — 토큰이 없거나 틀리면 {@code UNAUTHENTICATED}로 끊는다.
 *       토큰이 설정되지 않았으면 모든 호출을 끊는다(fail-closed).</li>
 *   <li>{@link #OFF} — 검사하지 않는다(로컬 디버깅·테스트용).</li>
 * </ol>
 */
public enum GrpcAuthMode {
    OFF, MONITOR, ENFORCE;

    /** 알 수 없는 값은 MONITOR로 — 오타 하나로 운영 gRPC가 전부 끊기거나(ENFORCE) 검사가 사라지지(OFF) 않게. */
    public static GrpcAuthMode parse(String raw) {
        if (raw == null || raw.isBlank()) return MONITOR;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return MONITOR;
        }
    }
}
