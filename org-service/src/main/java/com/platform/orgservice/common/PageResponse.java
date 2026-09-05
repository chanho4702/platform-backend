package com.platform.orgservice.common;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * 목록 응답 봉투. Spring Data {@code Page}를 그대로 직렬화하지 않는 이유는 그 JSON이
 * 구현 세부(pageable·sort·last·numberOfElements…)를 그대로 노출해 계약이 되기 때문이다 —
 * 프론트가 읽는 것은 네 개뿐이다.
 */
@Schema(description = "목록 응답 봉투. Spring Data Page의 구현 세부는 담지 않는다 — 프론트가 읽는 것은 네 개뿐이다.")
public record PageResponse<T>(
        @Schema(description = "이 페이지의 항목") List<T> items,
        @Schema(description = "0부터 세는 페이지 번호", example = "0") int page,
        @Schema(description = "페이지 크기", example = "20") int size,
        @Schema(description = "전체 항목 수", example = "137") long total) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements());
    }

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long total) {
        return new PageResponse<>(items, page, size, total);
    }
}
