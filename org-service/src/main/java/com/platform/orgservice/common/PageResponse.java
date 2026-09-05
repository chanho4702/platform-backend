package com.platform.orgservice.common;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * 목록 응답 봉투. Spring Data {@code Page}를 그대로 직렬화하지 않는 이유는 그 JSON이
 * 구현 세부(pageable·sort·last·numberOfElements…)를 그대로 노출해 계약이 되기 때문이다 —
 * 프론트가 읽는 것은 네 개뿐이다.
 */
public record PageResponse<T>(List<T> items, int page, int size, long total) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements());
    }

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long total) {
        return new PageResponse<>(items, page, size, total);
    }
}
