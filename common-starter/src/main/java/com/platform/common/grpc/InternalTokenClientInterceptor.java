package com.platform.common.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

/**
 * 나가는 모든 gRPC 호출의 메타데이터에 서비스 간 토큰({@value InternalTokenServerInterceptor#HEADER_NAME})을 싣는다(P-01).
 *
 * <p>채널에 한 번 건다: {@code ManagedChannelBuilder.forAddress(h, p).usePlaintext().intercept(interceptor).build()}.
 * 토큰이 비어 있으면 아무것도 싣지 않는다 — 서버가 MONITOR면 경고만, ENFORCE면 거부된다.
 */
public class InternalTokenClientInterceptor implements ClientInterceptor {

    private final String token;

    public InternalTokenClientInterceptor(String token) {
        this.token = token == null ? "" : token.trim();
    }

    public boolean hasToken() {
        return !token.isEmpty();
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                               CallOptions callOptions, Channel next) {
        ClientCall<ReqT, RespT> call = next.newCall(method, callOptions);
        if (token.isEmpty()) return call;
        return new ForwardingClientCall.SimpleForwardingClientCall<>(call) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                headers.put(InternalTokenServerInterceptor.HEADER, token);
                super.start(responseListener, headers);
            }
        };
    }
}
