package com.platform.searchservice.index;

import com.platform.proto.wiki.v1.AttachmentMeta;
import com.platform.proto.wiki.v1.PageContent;

import java.util.Locale;

/**
 * wiki gRPC 계약 → 색인 문서 변환.
 *
 * 이벤트 소비 경로와 백필 경로가 **같은 변환**을 써야 한다. 갈라지면 재색인 뒤에야 필드가
 * 달라진 게 드러나는데, 그때는 이미 별칭이 새 인덱스로 옮겨간 뒤다.
 */
public final class WikiDocuments {

    private WikiDocuments() {}

    public static PageDoc toDocument(PageContent page) {
        return new PageDoc(
                PageDoc.DOC_TYPE,
                page.getPageId(),
                page.getSpaceId(),
                page.getSpaceKey(),
                page.getSpaceName(),
                page.getTitle(),
                page.getContent(),
                page.getType().name().toLowerCase(Locale.ROOT),
                page.getStatus().name().toLowerCase(Locale.ROOT),
                page.getVersion(),
                page.getAuthorId(),
                page.getUpdatedAt());
    }

    public static AttachmentDoc toDocument(AttachmentMeta attachment) {
        return new AttachmentDoc(
                AttachmentDoc.DOC_TYPE,
                attachment.getAttachmentId(),
                attachment.getPageId(),
                attachment.getSpaceId(),
                attachment.getSpaceKey(),
                attachment.getSpaceName(),
                attachment.getFilename(),
                attachment.getContentType(),
                attachment.getSizeBytes(),
                attachment.getUploadedBy(),
                attachment.getCreatedAt());
    }
}
