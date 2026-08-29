package com.platform.searchservice.index;

/**
 * 페이지 색인 문서. 필드 이름은 `resources/opensearch/wiki-page.json` 매핑과 **정확히** 같아야
 * 한다 — 매핑이 `dynamic: strict`라 모르는 필드가 오면 색인이 거부된다(조용히 통과하지 않는
 * 쪽을 택했다: 필드 오타가 검색 결과에서만 드러나면 원인을 찾기 어렵다).
 *
 * 스페이스 표시명(key·name)은 비정규화해서 담는다 — 검색 결과를 그리려고 스페이스를 다시
 * 조회하지 않기 위해서다. 그 대가로 스페이스명이 바뀌면 SpaceUpdated로 갱신해야 한다.
 */
public record PageDoc(
        String docType,
        long pageId,
        long spaceId,
        String spaceKey,
        String spaceName,
        String title,
        String content,
        String type,
        String status,
        int version,
        long authorId,
        long updatedAt,
        /** 정규화된 라벨 이름. 검색의 라벨 필터가 쓴다 — 순서는 의미 없다. */
        java.util.List<String> labels
) {
    public static final String DOC_TYPE = "PAGE";
}
