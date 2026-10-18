package com.platform.common.grpc;

import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 들어오는 gRPC 호출의 서비스 간 토큰 검사(P-01). 서버에 서비스마다 건다:
 * {@code ServerInterceptors.intercept(service, interceptor)}.
 *
 * <p>모드는 {@link GrpcAuthMode}. MONITOR의 경고는 (메서드, 사유)별로 분당 한 줄로 묶는다 — 아직 토큰을 안 보내는
 * 호출자가 초당 수십 번 부르면 로그가 그것으로 덮이기 때문이다. 묶인 건수와 마지막 원격 주소를 함께 남겨
 * "누가 아직 안 보내는가"를 찾을 수 있게 한다. 토큰 값은 로그에 남기지 않는다.
 */
public class InternalTokenServerInterceptor implements ServerInterceptor {

    public static final String HEADER_NAME = "x-internal-token";
    public static final Metadata.Key<String> HEADER = Metadata.Key.of(HEADER_NAME, Metadata.ASCII_STRING_MARSHALLER);
    static final long WARN_INTERVAL_MS = 60_000;

    private static final Logger log = LoggerFactory.getLogger(InternalTokenServerInterceptor.class);

    private final GrpcAuthMode mode;
    private final byte[] token;
    private final LongSupplier clock;
    private final Map<String, AtomicLong> lastWarnAt = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> suppressed = new ConcurrentHashMap<>();

    public InternalTokenServerInterceptor(GrpcAuthMode mode, String token) {
        this(mode, token, System::currentTimeMillis);
    }

    InternalTokenServerInterceptor(GrpcAuthMode mode, String token, LongSupplier clock) {
        this.mode = mode == null ? GrpcAuthMode.MONITOR : mode;
        this.token = (token == null ? "" : token.trim()).getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
        if (this.mode != GrpcAuthMode.OFF && this.token.length == 0) {
            log.warn("서비스 간 gRPC 토큰(PLATFORM_GRPC_TOKEN)이 설정되지 않았다 — 모드 {}: {}", this.mode,
                    this.mode == GrpcAuthMode.ENFORCE ? "모든 gRPC 호출을 거부한다" : "검사 없이 통과시키고 경고만 남긴다");
        } else {
            log.info("서비스 간 gRPC 토큰 검사 모드: {}", this.mode);
        }
    }

    public GrpcAuthMode mode() {
        return mode;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
                                                                 ServerCallHandler<ReqT, RespT> next) {
        if (mode == GrpcAuthMode.OFF) return next.startCall(call, headers);

        String reason = reject(headers.get(HEADER));
        if (reason == null) return next.startCall(call, headers);

        String method = call.getMethodDescriptor().getFullMethodName();
        if (mode == GrpcAuthMode.ENFORCE) {
            warnThrottled(method, reason, call, "거부");
            call.close(Status.UNAUTHENTICATED.withDescription("내부 gRPC 토큰이 유효하지 않습니다"), new Metadata());
            return new ServerCall.Listener<>() {};
        }
        warnThrottled(method, reason, call, "통과(monitor)");
        return next.startCall(call, headers);
    }

    /** 통과면 null, 아니면 사유. */
    private String reject(String presented) {
        if (token.length == 0) return "server-token-unset";
        if (presented == null || presented.isBlank()) return "missing";
        boolean ok = MessageDigest.isEqual(token, presented.trim().getBytes(StandardCharsets.UTF_8));
        return ok ? null : "mismatch";
    }

    private void warnThrottled(String method, String reason, ServerCall<?, ?> call, String outcome) {
        String key = method + "|" + reason;
        long now = clock.getAsLong();
        AtomicLong last = lastWarnAt.computeIfAbsent(key, k -> new AtomicLong(Long.MIN_VALUE));
        AtomicLong skipped = suppressed.computeIfAbsent(key, k -> new AtomicLong());
        long prev = last.get();
        if (prev != Long.MIN_VALUE && now - prev < WARN_INTERVAL_MS) {
            skipped.incrementAndGet();
            return;
        }
        if (!last.compareAndSet(prev, now)) {
            skipped.incrementAndGet();
            return;
        }
        Object remote = call.getAttributes() == null ? null : call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
        log.warn("서비스 간 gRPC 토큰 {} — {} method={} remote={} (직전 1분 동안 같은 경고 {}건 생략)",
                reason, outcome, method, remote, skipped.getAndSet(0));
    }
}
