package com.carenexus.api.terminology.provider;

import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * ICD-10 terminology backed by a catalogue of common codes shipped with the application
 * ({@code terminology/icd10-subset.tsv} on the classpath) plus the {@link IcdCodeFormat} check:
 * a catalogue code is {@link CodeValidation#VERIFIED}, a well-formed code outside the subset is accepted as
 * {@link CodeValidation#UNVERIFIED}, anything else is {@link CodeValidation#INVALID}.
 *
 * File format: UTF-8, one entry per line, {@code CODE<TAB>Description}; blank lines and lines starting with
 * {@code #} are ignored. Every code must be well-formed and unique and every description 1 to 255 characters;
 * a broken file fails startup instead of serving a partial catalogue.
 *
 * Immutable after construction and safe for concurrent use.
 */
public class BundledIcd10Provider implements TerminologyProvider {

    public static final String PROVIDER_NAME = "bundled";
    public static final String DEFAULT_RESOURCE = "terminology/icd10-subset.tsv";
    public static final int MAX_DESCRIPTION_LENGTH = 255;

    private final List<Entry> entries;
    private final Map<String, String> descriptions;

    private BundledIcd10Provider(List<Entry> entries) {
        this.entries = List.copyOf(entries);
        Map<String, String> byCode = new TreeMap<>();
        for (Entry entry : entries) {
            byCode.put(entry.code(), entry.description());
        }
        this.descriptions = Collections.unmodifiableMap(byCode);
    }

    /** Loads the catalogue from a classpath resource, for example {@link #DEFAULT_RESOURCE}. */
    public static BundledIcd10Provider fromClasspath(String resource) {
        ClassPathResource file = new ClassPathResource(resource);
        if (!file.exists()) {
            throw new IllegalStateException("ICD-10 catalogue '" + resource + "' is not on the classpath");
        }
        try (InputStream in = file.getInputStream()) {
            return parse(resource, new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("ICD-10 catalogue '" + resource + "' could not be read", e);
        }
    }

    /**
     * Parses a catalogue in the file format described on the class.
     *
     * @param sourceName name used in error messages
     * @throws IllegalStateException when a line is malformed, a code repeats, or the catalogue is empty
     */
    public static BundledIcd10Provider parse(String sourceName, Reader reader) {
        TreeMap<String, Entry> byCode = new TreeMap<>();
        try (BufferedReader lines = new BufferedReader(reader)) {
            String line;
            int number = 0;
            while ((line = lines.readLine()) != null) {
                number++;
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab < 0) {
                    throw broken(sourceName, number, "expected CODE<TAB>Description");
                }
                String code = IcdCodeFormat.normalize(line.substring(0, tab));
                String description = line.substring(tab + 1).strip();
                if (!IcdCodeFormat.isWellFormed(code)) {
                    throw broken(sourceName, number, "'" + code + "' is not a well-formed ICD-10 code");
                }
                if (description.isEmpty() || description.length() > MAX_DESCRIPTION_LENGTH) {
                    throw broken(sourceName, number, "the description must be 1 to " + MAX_DESCRIPTION_LENGTH + " characters");
                }
                if (byCode.containsKey(code)) {
                    throw broken(sourceName, number, "code " + code + " is listed twice");
                }
                byCode.put(code, new Entry(code, description));
            }
        } catch (IOException e) {
            throw new IllegalStateException("ICD-10 catalogue '" + sourceName + "' could not be read", e);
        }
        if (byCode.isEmpty()) {
            throw new IllegalStateException("ICD-10 catalogue '" + sourceName + "' contains no codes");
        }
        return new BundledIcd10Provider(new ArrayList<>(byCode.values()));
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    /** Number of codes in the catalogue. */
    public int size() {
        return entries.size();
    }

    @Override
    public List<TerminologyCode> search(String query, int limit) {
        String normalized = normalizeQuery(query);
        if (normalized.isEmpty() || limit <= 0) {
            return List.of();
        }
        String compactQuery = normalized.indexOf(' ') < 0 ? IcdCodeFormat.compact(normalized) : "";
        String[] words = normalized.toLowerCase(Locale.ROOT).split(" ");

        List<TerminologyCode> codeMatches = new ArrayList<>();
        List<TerminologyCode> descriptionMatches = new ArrayList<>();
        for (Entry entry : entries) {
            if (!compactQuery.isEmpty() && entry.compactCode().startsWith(compactQuery)) {
                codeMatches.add(entry.toCode());
            } else if (containsAll(entry.lowerDescription(), words)) {
                descriptionMatches.add(entry.toCode());
            }
            if (codeMatches.size() >= limit) {
                break;
            }
        }
        List<TerminologyCode> result = new ArrayList<>(Math.min(limit, codeMatches.size() + descriptionMatches.size()));
        for (TerminologyCode code : codeMatches) {
            if (result.size() >= limit) {
                break;
            }
            result.add(code);
        }
        for (TerminologyCode code : descriptionMatches) {
            if (result.size() >= limit) {
                break;
            }
            result.add(code);
        }
        return result;
    }

    @Override
    public CodeValidation validate(String code) {
        if (!IcdCodeFormat.isWellFormed(code)) {
            return CodeValidation.INVALID;
        }
        return descriptions.containsKey(IcdCodeFormat.normalize(code)) ? CodeValidation.VERIFIED : CodeValidation.UNVERIFIED;
    }

    @Override
    public Optional<String> describe(String code) {
        String normalized = IcdCodeFormat.normalize(code);
        return normalized == null ? Optional.empty() : Optional.ofNullable(descriptions.get(normalized));
    }

    /** Trimmed, inner whitespace collapsed to single spaces; null becomes "". */
    public static String normalizeQuery(String query) {
        return query == null ? "" : query.strip().replaceAll("\\s+", " ");
    }

    private static boolean containsAll(String text, String[] words) {
        for (String word : words) {
            if (!text.contains(word)) {
                return false;
            }
        }
        return true;
    }

    private static IllegalStateException broken(String sourceName, int line, String reason) {
        return new IllegalStateException("ICD-10 catalogue '" + sourceName + "', line " + line + ": " + reason);
    }

    private record Entry(String code, String description, String compactCode, String lowerDescription) {
        Entry(String code, String description) {
            this(code, description, IcdCodeFormat.compact(code), description.toLowerCase(Locale.ROOT));
        }

        TerminologyCode toCode() {
            return new TerminologyCode(code, description);
        }
    }
}
