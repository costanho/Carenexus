package com.carenexus.api.documents.service;

import com.carenexus.api.documents.model.Document;
import com.carenexus.api.documents.model.DocumentTypes;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns the client's file name into the name used for downloads ({@code documents.original_filename}). The result
 * is never used as a storage key. Rules (contract section 2.1, "File name"):
 * <ol>
 *   <li>keep only the part after the last {@code /} or {@code \};</li>
 *   <li>remove control characters and {@code " < > : | ? * \ /}, collapse white space to one space, strip leading
 *       and trailing spaces and dots;</li>
 *   <li>use {@code document} when nothing is left;</li>
 *   <li>append the canonical extension of the detected type unless the name already ends in one of its extensions
 *       ({@code scan.exe} detected as PDF becomes {@code scan.exe.pdf});</li>
 *   <li>shorten the part before the final extension so the whole name is at most 150 characters.</li>
 * </ol>
 */
public final class DocumentFileNames {

    static final String FALLBACK = "document";

    private static final Pattern FORBIDDEN = Pattern.compile("[\\x00-\\x1F\\x7F\"<>:|?*\\\\/]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private DocumentFileNames() {
    }

    /**
     * @param clientFilename      file name sent by the client (may be null)
     * @param detectedContentType one of the allowed content types (lower case)
     */
    public static String sanitize(String clientFilename, String detectedContentType) {
        List<String> extensions = DocumentTypes.EXTENSIONS_BY_CONTENT_TYPE.get(detectedContentType);
        if (extensions == null) {
            throw new IllegalArgumentException("Content type is not allowed: " + detectedContentType);
        }

        String name = clientFilename == null ? "" : clientFilename;
        int lastSeparator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (lastSeparator >= 0) {
            name = name.substring(lastSeparator + 1);
        }
        name = FORBIDDEN.matcher(name).replaceAll("");
        name = WHITESPACE.matcher(name).replaceAll(" ");
        name = stripSpacesAndDots(name);
        if (name.isEmpty()) {
            name = FALLBACK;
        }

        String lower = name.toLowerCase(Locale.ROOT);
        String extension = extensions.stream().filter(lower::endsWith).findFirst().orElse(null);
        if (extension == null) {
            extension = extensions.get(0);
            name = name + extension;
        }

        if (name.length() > Document.ORIGINAL_FILENAME_MAX) {
            String base = name.substring(0, name.length() - extension.length());
            String suffix = name.substring(name.length() - extension.length());
            int maxBase = Document.ORIGINAL_FILENAME_MAX - suffix.length();
            int end = base.offsetByCodePoints(0, Math.min(base.codePointCount(0, base.length()), maxBase));
            while (end > maxBase) {
                // A surrogate pair straddles the limit: keep whole code points only.
                end = base.offsetByCodePoints(0, base.codePointCount(0, end) - 1);
            }
            base = stripSpacesAndDots(base.substring(0, end));
            name = (base.isEmpty() ? FALLBACK : base) + suffix;
        }
        return name;
    }

    private static String stripSpacesAndDots(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isSpaceOrDot(value.charAt(start))) {
            start++;
        }
        while (end > start && isSpaceOrDot(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isSpaceOrDot(char c) {
        return c == ' ' || c == '.';
    }
}
