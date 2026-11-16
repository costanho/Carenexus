package com.carenexus.api.common.dto;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Page/size clamping, the pagination envelope and the shared validation patterns (no Spring context).
 */
public class PageParamsTest {

    private static final Sort NEWEST = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

    @Test
    void defaults_whenNull() {
        Pageable pageable = PageParams.of(null, null, NEWEST);
        assertEquals(0, pageable.getPageNumber());
        assertEquals(PageParams.DEFAULT_SIZE, pageable.getPageSize());
        assertEquals(NEWEST, pageable.getSort());
    }

    @Test
    void negativePageBecomesZero_andSizeIsClampedTo1And100() {
        assertEquals(0, PageParams.of(-3, 20, NEWEST).getPageNumber());
        assertEquals(1, PageParams.of(0, 0, NEWEST).getPageSize());
        assertEquals(1, PageParams.of(0, -10, NEWEST).getPageSize());
        assertEquals(100, PageParams.of(0, 5000, NEWEST).getPageSize());
        assertEquals(37, PageParams.of(4, 37, NEWEST).getPageSize());
        assertEquals(4, PageParams.of(4, 37, NEWEST).getPageNumber());
    }

    @Test
    void customMaximum() {
        assertEquals(200, PageParams.of(0, 5000, 200, NEWEST).getPageSize());
        assertEquals(10, PageParams.of(0, null, 10, NEWEST).getPageSize(), "the default never exceeds the maximum");
        assertTrue(PageParams.of(0, 10, null).getSort().isUnsorted());
    }

    @Test
    void pageResponse_factories() {
        Pageable pageable = PageParams.of(1, 2, NEWEST);
        PageImpl<Integer> page = new PageImpl<>(List.of(3, 4), pageable, 5);

        PageResponse<String> mapped = PageResponse.of(page, i -> "row-" + i);
        assertEquals(List.of("row-3", "row-4"), mapped.getContent());
        assertEquals(1, mapped.getPage());
        assertEquals(2, mapped.getSize());
        assertEquals(5, mapped.getTotalElements());
        assertEquals(3, mapped.getTotalPages());

        PageResponse<String> replaced = PageResponse.of(page, List.of("a", "b"));
        assertEquals(List.of("a", "b"), replaced.getContent());
        assertEquals(5, replaced.getTotalElements());

        PageResponse<String> empty = PageResponse.empty(0, 20);
        assertTrue(empty.getContent().isEmpty());
        assertEquals(0, empty.getTotalElements());
        assertEquals(0, empty.getTotalPages());
    }

    @Test
    void validationPatterns() {
        assertTrue("PAT-00001".matches(ValidationPatterns.SYSTEM_ID));
        assertTrue("DOC-123456".matches(ValidationPatterns.SYSTEM_ID));
        assertTrue("CGV-00010".matches(ValidationPatterns.SYSTEM_ID));
        assertTrue("ADM-00001".matches(ValidationPatterns.SYSTEM_ID));
        assertFalse("PAT-0001".matches(ValidationPatterns.SYSTEM_ID));
        assertFalse("pat-00001".matches(ValidationPatterns.SYSTEM_ID));
        assertFalse("NUR-00001".matches(ValidationPatterns.SYSTEM_ID));
        assertFalse("PAT-00001 ".matches(ValidationPatterns.SYSTEM_ID));

        assertTrue("+254712345678".matches(ValidationPatterns.PHONE));
        assertTrue("0712 345 678".matches(ValidationPatterns.PHONE));
        assertTrue("(020) 123-4567".matches(ValidationPatterns.PHONE));
        assertFalse("12345".matches(ValidationPatterns.PHONE));
        assertFalse("+2547123456789012345".matches(ValidationPatterns.PHONE));
        assertFalse("phone".matches(ValidationPatterns.PHONE));

        assertTrue("00:00".matches(ValidationPatterns.TIME_HH_MM));
        assertTrue("09:30".matches(ValidationPatterns.TIME_HH_MM));
        assertTrue("23:59".matches(ValidationPatterns.TIME_HH_MM));
        assertFalse("24:00".matches(ValidationPatterns.TIME_HH_MM));
        assertFalse("9:30".matches(ValidationPatterns.TIME_HH_MM));
        assertFalse("09:60".matches(ValidationPatterns.TIME_HH_MM));
    }
}
