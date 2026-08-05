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
        String title,
        String filename,
        List<String> highlights,
        String updatedAt,
        double score
) {}
