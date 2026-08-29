package com.platform.searchservice.search;

import java.util.List;

/** schema.graphqls의 SearchHit 응답 모델. */
public record SearchHit(
        String id,
        DocType docType,
        String spaceId,
        String spaceKey,
        String spaceName,
        String pageId,
        /** PAGE만 채워진다 — 결과 목록이 폴더와 문서를 다른 아이콘으로 그린다. */
        String pageType,
        String title,
        String filename,
        List<String> highlights,
        String updatedAt,
        double score
) {}
