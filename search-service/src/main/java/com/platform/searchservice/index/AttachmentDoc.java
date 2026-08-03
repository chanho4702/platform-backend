package com.platform.searchservice.index;

/**
 * 첨부 색인 문서. 필드 이름은 `resources/opensearch/wiki-attachment.json` 매핑과 정확히 같다
 * (`dynamic: strict`).
 *
 * spaceId가 있는 이유: 권한 필터가 스페이스 단위라 첨부에도 반드시 있어야 한다.
 * Attachment 엔티티에는 없는 값이라 wiki-backend가 페이지를 조인해 채워 보낸다.
 */
public record AttachmentDoc(
        String docType,
        long attachmentId,
        long pageId,
        long spaceId,
        String spaceKey,
        String spaceName,
        String filename,
        String contentType,
        long sizeBytes,
        long uploadedBy,
        long updatedAt
) {
    public static final String DOC_TYPE = "ATTACHMENT";
}
