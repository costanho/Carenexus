package com.carenexus.api.common.dto;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * The one place that turns {@code page} / {@code size} request parameters into a {@link Pageable}.
 * A negative page becomes 0; size is clamped to 1..{@link #MAX_SIZE} (or the given maximum); null means the default.
 * Always pass a deterministic {@link Sort} (end it with the primary key) so pages never overlap.
 */
public final class PageParams {

    public static final int DEFAULT_SIZE = 20, MAX_SIZE = 100;

    private PageParams() {
    }

    public static Pageable of(Integer page, Integer size, Sort sort) {
        return of(page, size, MAX_SIZE, sort);
    }

    public static Pageable of(Integer page, Integer size, int maxSize, Sort sort) {
        int max = Math.max(maxSize, 1);
        int safePage = page == null ? 0 : Math.max(page, 0);
        int safeSize = size == null ? Math.min(DEFAULT_SIZE, max) : Math.min(Math.max(size, 1), max);
        return PageRequest.of(safePage, safeSize, sort == null ? Sort.unsorted() : sort);
    }
}
