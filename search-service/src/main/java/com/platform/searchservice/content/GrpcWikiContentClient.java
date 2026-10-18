package com.platform.searchservice.content;

import com.platform.proto.wiki.v1.AttachmentMeta;
import com.platform.proto.wiki.v1.FilterVisiblePagesRequest;
import com.platform.proto.wiki.v1.GetAttachmentMetaRequest;
import com.platform.proto.wiki.v1.GetPageContentRequest;
import com.platform.proto.wiki.v1.ListAttachmentsRequest;
import com.platform.proto.wiki.v1.ListPageContentsRequest;
import com.platform.proto.wiki.v1.PageContent;
import com.platform.proto.wiki.v1.WikiContentServiceGrpc;
import com.platform.common.error.ServiceUnavailableException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;

import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * wiki-backend 콘텐츠 gRPC 클라이언트(색인 조달·검색 후필터).
 *
 * <p>모든 호출에 데드라인을 건다(P-04). 단건 호출은 {@code platform.wiki-grpc.deadline-seconds},
 * 스페이스 전체를 흘려보내는 백필 스트림은 {@code platform.wiki-grpc.stream-deadline-seconds}(스트림 전체 상한).
 * 데드라인이 없으면 wiki가 행(hang)할 때 검색 요청 스레드와 색인 소비 스레드가 함께 멈춘다.
 *
 * <p>예외 메시지는 사용자·관리 화면에 그대로 보일 수 있어 고정 문구만 싣는다(P-18). 페이지 id·gRPC 상태 같은
 * 세부는 로그와 예외 원인(cause)에 남긴다.
 */
@Slf4j
public class GrpcWikiContentClient implements WikiContentClient {

    private final WikiContentServiceGrpc.WikiContentServiceBlockingStub stub;
    private final long deadlineSeconds;
    private final long streamDeadlineSeconds;

    public GrpcWikiContentClient(WikiContentServiceGrpc.WikiContentServiceBlockingStub stub,
                                 long deadlineSeconds, long streamDeadlineSeconds) {
        if (deadlineSeconds <= 0 || streamDeadlineSeconds <= 0) {
            throw new IllegalArgumentException("deadline seconds must be positive");
        }
        this.stub = stub;
        this.deadlineSeconds = deadlineSeconds;
        this.streamDeadlineSeconds = streamDeadlineSeconds;
    }

    private WikiContentServiceGrpc.WikiContentServiceBlockingStub unary() {
        return stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS);
    }

    private WikiContentServiceGrpc.WikiContentServiceBlockingStub streaming() {
        return stub.withDeadlineAfter(streamDeadlineSeconds, TimeUnit.SECONDS);
    }

    @Override
    public Optional<PageContent> getPage(long pageId) {
        try {
            return Optional.of(unary().getPageContent(
                    GetPageContentRequest.newBuilder().setPageId(pageId).build()));
        } catch (StatusRuntimeException e) {
            // NOT_FOUND만 "지워졌다"로 읽는다. 이 구분이 무너지면 wiki-backend가 잠깐 죽은 사이
            // 들어온 이벤트들이 색인을 통째로 지운다.
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return Optional.empty();
            }
            log.warn("위키 콘텐츠 조달 실패: page={} status={}", pageId, e.getStatus().getCode());
            throw new ServiceUnavailableException("위키 콘텐츠를 가져오지 못했습니다", e);
        }
    }

    @Override
    public Optional<AttachmentMeta> getAttachment(long attachmentId) {
        try {
            return Optional.of(unary().getAttachmentMeta(
                    GetAttachmentMetaRequest.newBuilder().setAttachmentId(attachmentId).build()));
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return Optional.empty();
            }
            log.warn("위키 첨부 조달 실패: attachment={} status={}", attachmentId, e.getStatus().getCode());
            throw new ServiceUnavailableException("위키 첨부 정보를 가져오지 못했습니다", e);
        }
    }

    @Override
    public void streamPages(long spaceId, Consumer<PageContent> consumer) {
        drain(() -> streaming().listPageContents(ListPageContentsRequest.newBuilder().setSpaceId(spaceId).build()),
                consumer, "페이지", spaceId);
    }

    @Override
    public void streamAttachments(long spaceId, Consumer<AttachmentMeta> consumer) {
        drain(() -> streaming().listAttachments(ListAttachmentsRequest.newBuilder().setSpaceId(spaceId).build()),
                consumer, "첨부", spaceId);
    }

    private static <T> void drain(java.util.function.Supplier<Iterator<T>> open, Consumer<T> consumer,
                                  String what, long spaceId) {
        try {
            Iterator<T> it = open.get();
            while (it.hasNext()) consumer.accept(it.next());
        } catch (StatusRuntimeException e) {
            // 스트림 중간에 끊기면 백필은 불완전하다 — 조용히 끝내면 "다 됐다"로 오인된다
            log.warn("{} 백필 스트림 중단: space={} status={}", what, spaceId, e.getStatus().getCode());
            throw new ServiceUnavailableException(what + " 백필 스트림이 중단됐습니다", e);
        }
    }

    @Override
    public java.util.Set<Long> filterVisiblePages(long userId, java.util.Collection<Long> pageIds) {
        try {
            return new java.util.HashSet<>(unary().filterVisiblePages(FilterVisiblePagesRequest.newBuilder()
                    .setUserId(userId)
                    .addAllPageIds(pageIds)
                    .build()).getVisiblePageIdsList());
        } catch (StatusRuntimeException e) {
            // 어떤 실패든 필터 없이 결과를 열면 제한 페이지가 샌다 — 검색 자체를 닫는다(fail-closed)
            log.error("위키 권한 필터 실패 — 검색 503 전파: user={} status={}", userId, e.getStatus().getCode());
            throw new ServiceUnavailableException("위키 서비스에 연결할 수 없습니다", e);
        }
    }
}
