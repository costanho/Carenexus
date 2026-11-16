package com.carenexus.api.delivery;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the plain-text message templates on the classpath: {@code messages/<templateId>.txt}.
 *
 * <pre>
 * Subject: Reset your CareNexus password
 *
 * Hello {{firstName}},
 * ...
 * </pre>
 *
 * The first line is {@code Subject: ...}, the second line is blank, everything after it is the body. Placeholders are
 * {@code {{name}}} (letters, digits, {@code _ . -}); values are inserted verbatim and a missing value renders empty.
 * An unknown or malformed template fails at once with an {@link IllegalStateException}, so a caller finds out
 * inside its own transaction and nothing half-rendered is queued.
 *
 * Each slice ships its own files with a module prefix ({@code account-password-reset.txt}); bodies never contain
 * clinical values.
 */
@Component
public class TemplateRenderer {

    static final String LOCATION = "messages/";
    private static final String SUBJECT_PREFIX = "Subject:";
    private static final Pattern TEMPLATE_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,79}$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");

    record Template(String subject, String body) {
    }

    private final Map<String, Template> cache = new ConcurrentHashMap<>();

    /**
     * @param templateId file name without {@code .txt}: lower-case letters, digits and hyphens
     * @param model      placeholder values; may be null
     * @throws IllegalStateException when the template does not exist or is malformed
     */
    public RenderedTemplate render(String templateId, Map<String, String> model) {
        Template template = load(templateId);
        Map<String, String> values = model == null ? Map.of() : model;
        // A value never adds a line to the subject (it becomes a mail header).
        String subject = fill(template.subject(), values).replaceAll("[\\r\\n]+", " ").trim();
        return new RenderedTemplate(subject, fill(template.body(), values));
    }

    /** True when {@code messages/<templateId>.txt} exists and is well-formed. */
    public boolean exists(String templateId) {
        try {
            load(templateId);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private Template load(String templateId) {
        if (templateId == null || !TEMPLATE_ID.matcher(templateId).matches()) {
            throw new IllegalStateException("Invalid message template id");
        }
        Template cached = cache.get(templateId);
        if (cached != null) {
            return cached;
        }
        Template parsed = parse(templateId, read(templateId));
        cache.put(templateId, parsed);
        return parsed;
    }

    private static String read(String templateId) {
        ClassPathResource resource = new ClassPathResource(LOCATION + templateId + ".txt");
        if (!resource.exists()) {
            throw new IllegalStateException("Unknown message template '" + templateId + "' (expected classpath:"
                    + LOCATION + templateId + ".txt)");
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Message template '" + templateId + "' could not be read", e);
        }
    }

    static Template parse(String templateId, String raw) {
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        int firstBreak = text.indexOf('\n');
        String firstLine = firstBreak < 0 ? text : text.substring(0, firstBreak);
        if (!firstLine.startsWith(SUBJECT_PREFIX)) {
            throw new IllegalStateException("Message template '" + templateId + "' must start with a 'Subject:' line");
        }
        String subject = firstLine.substring(SUBJECT_PREFIX.length()).trim();
        if (subject.isEmpty()) {
            throw new IllegalStateException("Message template '" + templateId + "' has an empty subject");
        }
        int secondBreak = firstBreak < 0 ? -1 : text.indexOf('\n', firstBreak + 1);
        if (secondBreak < 0 || !text.substring(firstBreak + 1, secondBreak).isBlank()) {
            throw new IllegalStateException("Message template '" + templateId
                    + "' needs a blank line between the subject and the body");
        }
        String body = text.substring(secondBreak + 1);
        if (body.isBlank()) {
            throw new IllegalStateException("Message template '" + templateId + "' has no body");
        }
        return new Template(subject, body);
    }

    private static String fill(String text, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder(text.length() + 64);
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
