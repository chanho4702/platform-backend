package com.platform.searchservice.search;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** schema.graphqls의 SearchInput과 같은 입력 모델. */
public record SearchInput(
        String query,
        List<String> spaceIds,
        List<DocType> docTypes,
        Boolean includeDrafts,
        /** 마지막 수정자 — 색인의 authorId와 대조한다. 첨부에는 없는 필드라 페이지만 걸린다. */
        List<String> authorIds,
        /** 이 시각 이후 수정된 문서만(ISO-8601). */
        String updatedAfter,
        /** 이 시각 이전 수정된 문서만(ISO-8601). */
        String updatedBefore,
        /** 라벨 — 여럿이면 OR(하나라도 붙은 문서). 첨부에는 없는 필드라 페이지만 걸린다. */
        List<String> labels,
        /** 정렬. null이면 관련도. */
        SearchSort sort,
        Integer page,
        Integer size,
        /**
         * 문서 종류 필터 — PAGE·FOLDER·BLOG의 부분집합. 비우면 전부. PAGE 결과에만 걸리고 첨부는 그대로 둔다
         * (첨부는 docTypes로 거른다). 위키 라이트 검색(wiki-backend SearchInput.pageTypes)과 같은 의미다.
         */
        List<String> pageTypes
) {
    /** 색인의 {@code type} 값(WikiDocuments가 소문자로 넣는다). 정본은 wiki의 PageType 열거. */
    static final Set<String> PAGE_TYPES = Set.of("PAGE", "FOLDER", "BLOG");

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 100;

    public Set<Long> requestedSpaceIds() {
        if (spaceIds == null || spaceIds.isEmpty()) return Set.of();
        Set<Long> parsed = new LinkedHashSet<>();
        for (String raw : spaceIds) {
            try {
                parsed.add(Long.parseLong(raw));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("spaceIds는 숫자 ID여야 합니다: " + raw, e);
            }
        }
        return Set.copyOf(parsed);
    }

    /** spaceIds와 같은 규칙 — 숫자 id가 아니면 조용히 무시하지 않고 거부한다. */
    public Set<Long> requestedAuthorIds() {
        if (authorIds == null || authorIds.isEmpty()) return Set.of();
        Set<Long> parsed = new LinkedHashSet<>();
        for (String raw : authorIds) {
            try {
                parsed.add(Long.parseLong(raw));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("authorIds는 숫자 ID여야 합니다: " + raw, e);
            }
        }
        return Set.copyOf(parsed);
    }

    /** 색인의 updatedAt은 epoch_millis다 — 경계값을 밀리초로 눕혀 범위 질의에 넣는다. */
    public Long updatedAfterMillis() {
        return toMillis(updatedAfter, "updatedAfter");
    }

    public Long updatedBeforeMillis() {
        return toMillis(updatedBefore, "updatedBefore");
    }

    private static Long toMillis(String raw, String field) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return java.time.Instant.parse(raw).toEpochMilli();
        } catch (java.time.format.DateTimeParseException e) {
            // 날짜만 온 경우(2026-08-01)도 받아준다 — 그 날의 시작으로 읽는다.
            try {
                return java.time.LocalDate.parse(raw).atStartOfDay(java.time.ZoneOffset.UTC)
                        .toInstant().toEpochMilli();
            } catch (java.time.format.DateTimeParseException ignored) {
                throw new IllegalArgumentException(field + "는 ISO-8601 시각이어야 합니다: " + raw, e);
            }
        }
    }

    /** 저장할 때와 같은 규칙으로 정규화한다 — 대소문자만 달라 안 걸리면 사용자는 이유를 모른다. */
    public List<String> normalizedLabels() {
        if (labels == null || labels.isEmpty()) return List.of();
        return labels.stream()
                .filter(java.util.Objects::nonNull)
                .map(raw -> raw.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\s+", "-"))
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }

    /**
     * 요청한 문서 종류(대문자, 중복 제거). 비었으면 거르지 않는다. 모르는 값은 조용히 버리지 않고 거부한다 —
     * 라이트 검색과 같은 문구.
     */
    public Set<String> requestedPageTypes() {
        if (pageTypes == null || pageTypes.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String raw : pageTypes) {
            String value = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
            if (!PAGE_TYPES.contains(value)) {
                throw new IllegalArgumentException("pageTypes는 PAGE·FOLDER·BLOG 중에서 고르세요: " + raw);
            }
            out.add(value);
        }
        return Set.copyOf(out);
    }

    public SearchSort normalizedSort() {
        return sort == null ? SearchSort.RELEVANCE : sort;
    }

    public boolean draftsIncluded() {
        return Boolean.TRUE.equals(includeDrafts);
    }

    public int normalizedPage() {
        return Math.max(page == null ? 0 : page, 0);
    }

    public int normalizedSize() {
        int requested = size == null ? DEFAULT_SIZE : size;
        return Math.max(0, Math.min(requested, MAX_SIZE));
    }
}
