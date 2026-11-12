package com.carenexus.api.terminology.provider;

import java.util.List;
import java.util.Optional;

/**
 * Clinical terminology seam (ICD-10). The default implementation is {@link BundledIcd10Provider}, a catalogue of
 * common codes shipped with the application plus a format check; a licensed ICD service or a full code set can be
 * plugged in later as another implementation selected by {@code carenexus.terminology.provider}.
 *
 * Codes are handled in their dotted, upper-case form ({@code J06.9}, {@code I10}).
 */
public interface TerminologyProvider {

    /** Provider id, for example {@code "bundled"}. */
    String providerName();

    /**
     * Catalogue entries matching {@code query}, ranked: entries whose code starts with the query (the dot is ignored
     * on both sides) first, then entries whose description contains every word of the query; inside each group by
     * code ascending. At most {@code limit} rows. A blank query returns nothing.
     */
    List<TerminologyCode> search(String query, int limit);

    /**
     * {@link CodeValidation#VERIFIED} when the code is in the catalogue, {@link CodeValidation#UNVERIFIED} when it is
     * a well-formed ICD-10 code outside it, {@link CodeValidation#INVALID} otherwise. The code is trimmed and
     * upper-cased first.
     */
    CodeValidation validate(String code);

    /** The catalogue description of a code (trimmed and upper-cased first), when the catalogue has it. */
    Optional<String> describe(String code);
}
