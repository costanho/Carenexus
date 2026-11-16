package com.carenexus.api.terminology.service;

import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.terminology.dto.response.IcdCodeResponse;
import com.carenexus.api.terminology.provider.BundledIcd10Provider;
import com.carenexus.api.terminology.provider.TerminologyCode;
import com.carenexus.api.terminology.provider.TerminologyProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ICD-10 type-ahead for clinicians. DOCTOR and ADMIN only; no patient data is involved, so nothing is audited.
 *
 * The provider is asked for at most {@link #MAX_MATCHES} ranked matches, which are paged in memory: a search never
 * reports more than 200 matches. A query shorter than {@link #MIN_QUERY_LENGTH} characters (after trimming and
 * collapsing whitespace) answers an empty page, so a debounced type-ahead needs no special case.
 */
@Service
@RequiredArgsConstructor
public class TerminologyService {

    public static final int MAX_MATCHES = 200;
    public static final int MIN_QUERY_LENGTH = 2;
    public static final int MAX_QUERY_LENGTH = 100;

    private final TerminologyProvider terminologyProvider;
    private final AuthorizationService authorizationService;

    public PageResponse<IcdCodeResponse> searchIcd(String query, int page, int size) {
        authorizationService.requireRole(AuthorizationService.DOCTOR, AuthorizationService.ADMIN);
        Pageable pageable = PageParams.of(page, size, null);
        int pageNumber = pageable.getPageNumber();
        int pageSize = pageable.getPageSize();

        String normalized = BundledIcd10Provider.normalizeQuery(query);
        if (normalized.length() < MIN_QUERY_LENGTH) {
            return PageResponse.empty(pageNumber, pageSize);
        }

        List<TerminologyCode> matches = terminologyProvider.search(normalized, MAX_MATCHES);
        int total = Math.min(matches.size(), MAX_MATCHES);
        long from = (long) pageNumber * pageSize;
        List<IcdCodeResponse> content = from >= total
                ? List.of()
                : matches.subList((int) from, (int) Math.min(from + pageSize, total)).stream()
                        .map(code -> new IcdCodeResponse(code.code(), code.description()))
                        .toList();
        return PageResponse.<IcdCodeResponse>builder()
                .content(content)
                .page(pageNumber)
                .size(pageSize)
                .totalElements(total)
                .totalPages((total + pageSize - 1) / pageSize)
                .build();
    }
}
