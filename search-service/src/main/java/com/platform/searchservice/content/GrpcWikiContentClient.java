package com.platform.searchservice.content;

import com.platform.proto.wiki.v1.AttachmentMeta;
import com.platform.proto.wiki.v1.GetPageContentRequest;
import com.platform.proto.wiki.v1.ListAttachmentsRequest;
import com.platform.proto.wiki.v1.ListPageContentsRequest;
import com.platform.proto.wiki.v1.PageContent;
import com.platform.proto.wiki.v1.WikiContentServiceGrpc;
import com.platform.searchservice.common.ServiceUnavailableException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Iterator;
import java.util.Optional;
import java.util.function.Consumer;

@Slf4j
@RequiredArgsConstructor
public class GrpcWikiContentClient implements WikiContentClient {

    private final WikiContentServiceGrpc.WikiContentServiceBlockingStub stub;

    @Override
    public Optional<PageContent> getPage(long pageId) {
        try {
            return Optional.of(stub.getPageContent(
                    GetPageContentRequest.newBuilder().setPageId(pageId).build()));
        } catch (StatusRuntimeException e) {
            // NOT_FOUND만 "지워졌다"로 읽는다. 이 구분이 무너지면 wiki-backend가 잠깐 죽은 사이
            // 들어온 이벤트들이 색인을 통째로 지운다.
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return Optional.empty();
            }
            throw new ServiceUnavailableException(
                    "위키 콘텐츠 조달 실패: page=" + pageId + " status=" + e.getStatus().getCode());
        }
    }

    @Override
    public void streamPages(long spaceId, Consumer<PageContent> consumer) {
        drain(stub.listPageContents(ListPageContentsRequest.newBuilder().setSpaceId(spaceId).build()),
                consumer, "페이지");
    }

    @Override
    public void streamAttachments(long spaceId, Consumer<AttachmentMeta> consumer) {
        drain(stub.listAttachments(ListAttachmentsRequest.newBuilder().setSpaceId(spaceId).build()),
                consumer, "첨부");
    }

    private static <T> void drain(Iterator<T> it, Consumer<T> consumer, String what) {
        try {
            while (it.hasNext()) consumer.accept(it.next());
        } catch (StatusRuntimeException e) {
            // 스트림 중간에 끊기면 백필은 불완전하다 — 조용히 끝내면 "다 됐다"로 오인된다
            throw new ServiceUnavailableException(
                    what + " 백필 스트림이 중단됐습니다: " + e.getStatus().getCode());
        }
    }
}
