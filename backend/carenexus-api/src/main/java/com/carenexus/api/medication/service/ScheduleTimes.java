package com.carenexus.api.medication.service;

import com.carenexus.api.common.dto.ValidationPatterns;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Daily dose times: parsing, normalising (distinct, ascending, "HH:mm") and deriving them from a free-text
 * frequency (contract be-medication 3.3).
 */
public final class ScheduleTimes {

    public static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private static final Pattern TIME = Pattern.compile(ValidationPatterns.TIME_HH_MM);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final List<String> NO_PLAN_WORDS = List.of("week", "month", "needed", "prn", "alternate", "other day");
    private static final List<String> DAILY_MARKERS =
            List.of("daily", "a day", "per day", "/day", "every day", "each day", "nightly");

    private static final Pattern ABBREVIATION_4 = Pattern.compile("\\b(qds|qid)\\b");
    private static final Pattern ABBREVIATION_3 = Pattern.compile("\\b(tds|tid)\\b");
    private static final Pattern ABBREVIATION_2 = Pattern.compile("\\b(bd|bid)\\b");
    private static final Pattern ABBREVIATION_1 = Pattern.compile("\\b(od|qd)\\b");
    private static final Pattern COUNT_4 = Pattern.compile("\\b(four times|4 times|4x)\\b");
    private static final Pattern COUNT_3 = Pattern.compile("\\b(three times|3 times|3x|thrice)\\b");
    private static final Pattern COUNT_2 = Pattern.compile("\\b(twice|two times|2 times|2x)\\b");
    private static final Pattern COUNT_1 = Pattern.compile("\\b(once|one time|1 time|1x)\\b");

    private ScheduleTimes() {
    }

    /** True when {@code value} is a 24-hour, zero padded "HH:mm" time. */
    public static boolean isValid(String value) {
        return value != null && TIME.matcher(value).matches();
    }

    /**
     * Distinct, ascending times from "HH:mm" strings. Invalid or null entries are dropped; callers validate input
     * that comes from a client before calling this.
     */
    public static List<LocalTime> normalise(Collection<String> values) {
        TreeSet<LocalTime> times = new TreeSet<>();
        if (values != null) {
            for (String value : values) {
                if (isValid(value)) {
                    times.add(LocalTime.parse(value, HH_MM));
                }
            }
        }
        return List.copyOf(times);
    }

    public static List<String> format(Collection<LocalTime> times) {
        return times.stream().map(t -> t.format(HH_MM)).toList();
    }

    public static String toJson(Collection<LocalTime> times) {
        try {
            return JSON.writeValueAsString(format(times));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise schedule times", e);
        }
    }

    /** Times of a stored plan (JSON array of "HH:mm" strings); an unreadable value counts as no times. */
    public static List<LocalTime> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = JSON.readValue(json, new TypeReference<List<String>>() { });
            return normalise(values);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /**
     * Daily times derived from a free-text frequency, or an empty list when no daily count can be read from it
     * (weekly, as needed, every 8 hours, ...).
     */
    public static List<LocalTime> deriveFromFrequency(String frequency) {
        if (frequency == null) {
            return List.of();
        }
        String text = frequency.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
        if (text.isEmpty() || NO_PLAN_WORDS.stream().anyMatch(text::contains)) {
            return List.of();
        }
        int count = abbreviationCount(text);
        if (count == 0 && DAILY_MARKERS.stream().anyMatch(text::contains)) {
            count = dailyCount(text);
        }
        return timesFor(count, text);
    }

    private static int abbreviationCount(String text) {
        if (ABBREVIATION_4.matcher(text).find()) return 4;
        if (ABBREVIATION_3.matcher(text).find()) return 3;
        if (ABBREVIATION_2.matcher(text).find()) return 2;
        if (ABBREVIATION_1.matcher(text).find()) return 1;
        return 0;
    }

    private static int dailyCount(String text) {
        if (COUNT_4.matcher(text).find()) return 4;
        if (COUNT_3.matcher(text).find()) return 3;
        if (COUNT_2.matcher(text).find()) return 2;
        if (COUNT_1.matcher(text).find()) return 1;
        if (text.startsWith("daily") || text.startsWith("every day") || text.startsWith("nightly")) return 1;
        return 0;
    }

    private static List<LocalTime> timesFor(int count, String text) {
        List<String> times = new ArrayList<>();
        switch (count) {
            case 1 -> times.add(text.contains("night") || text.contains("bedtime") || text.contains("evening")
                    ? "20:00" : "08:00");
            case 2 -> times.addAll(List.of("08:00", "20:00"));
            case 3 -> times.addAll(List.of("08:00", "14:00", "20:00"));
            case 4 -> times.addAll(List.of("08:00", "12:00", "16:00", "20:00"));
            default -> {
                return List.of();
            }
        }
        return normalise(times.stream().filter(Objects::nonNull).toList());
    }
}
