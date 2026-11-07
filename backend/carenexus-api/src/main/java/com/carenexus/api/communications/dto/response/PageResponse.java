package com.carenexus.api.communications.dto.response;

import lombok.*;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Pagination envelope: {@code { content, page, size, totalElements, totalPages }}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PageResponse<T> {

    private List<T> content;

    private int page;

    private int size;

    private long totalElements;

    private int totalPages;

    public static <E, T> PageResponse<T> of(Page<E> source, Function<E, T> mapper) {
        return of(source, source.getContent().stream().map(mapper).toList());
    }

    public static <T> PageResponse<T> of(Page<?> source, List<T> content) {
        return PageResponse.<T>builder()
                .content(content)
                .page(source.getNumber())
                .size(source.getSize())
                .totalElements(source.getTotalElements())
                .totalPages(source.getTotalPages())
                .build();
    }

    public static <T> PageResponse<T> empty(int page, int size) {
        return PageResponse.<T>builder()
                .content(List.of())
                .page(page)
                .size(size)
                .totalElements(0)
                .totalPages(0)
                .build();
    }
}
