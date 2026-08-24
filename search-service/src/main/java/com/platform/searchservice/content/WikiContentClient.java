package com.platform.searchservice.content;

import com.platform.proto.wiki.v1.AttachmentMeta;
import com.platform.proto.wiki.v1.PageContent;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * wiki-backend 본문 조달 창구(proto v0.5.0 `wiki.v1`).
 *
 * 이벤트는 본문을 싣지 않으므로("무엇이 변했나"만 알린다) 색인기가 여기서 따로 가져온다.
 */
public interface WikiContentClient {

    /**
     * 단건 조달.
     *
     * @return 페이지가 없으면 empty — **삭제된 것으로 간주해 색인에서 뺀다.**
     *         조달 자체가 불가능한 경우(wiki-backend 다운 등)는 예외를 던진다:
     *         "없음"과 "못 물어봄"을 같게 취급하면 서비스가 잠깐 죽은 사이 색인이 통째로 지워진다.
     */
    Optional<PageContent> getPage(long pageId);

    /**
     * 첨부 단건 조달.
     *
     * @return 첨부가 없으면 empty. 조달 실패와는 구분하며 실패는 예외로 전파한다.
     */
    Optional<AttachmentMeta> getAttachment(long attachmentId);

    /** 백필 — 전량 스트리밍. spaceId=0이면 전 스페이스. */
    void streamPages(long spaceId, Consumer<PageContent> consumer);

    /** 백필 — 첨부 전량 스트리밍. spaceId=0이면 전 스페이스. */
    void streamAttachments(long spaceId, Consumer<AttachmentMeta> consumer);

    /**
     * 검색 결과 권한 후필터(W18) — 사용자가 볼 수 있는 페이지 id만 남긴다.
     * 실패는 열지 않는다(제한 페이지 누출 방지) — 호출부가 ServiceUnavailable로 닫는다.
     */
    java.util.Set<Long> filterVisiblePages(long userId, java.util.Collection<Long> pageIds);
}
