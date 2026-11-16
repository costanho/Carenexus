package com.carenexus.api.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * The one pagination envelope of the API: {@code { content, page, size, totalElements, totalPages }}.
 * Build the {@code Pageable} with {@link PageParams} so every list clamps page and size the same way.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "One page of a list")
public class PageResponse<T> {

    @Schema(description = "Rows of this page")
    private List<T> content;

    @Schema(description = "Zero-based page index", example = "0")
    private int page;

    @Schema(description = "Requested page size after clamping (1..100)", example = "20")
    private int size;

    @Schema(description = "Total number of rows across all pages", example = "57")
    private long totalElements;

    @Schema(description = "Total number of pages", example = "3")
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
