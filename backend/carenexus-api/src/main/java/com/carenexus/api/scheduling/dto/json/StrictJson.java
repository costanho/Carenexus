package com.carenexus.api.scheduling.dto.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * Field deserializers for the scheduling request bodies that refuse a value of the wrong JSON type instead of letting
 * Jackson coerce it (a number for a time, {@code "30"} or {@code 30.5} for a number, a date-time with an offset).
 * A refused value surfaces as {@code 400 Malformed request body}, as the API contract requires.
 * JSON {@code null} never reaches them: it stays null and Bean Validation reports it.
 */
public final class StrictJson {

    private StrictJson() {
    }

    /** Only a JSON string. */
    public static class Text extends StdScalarDeserializer<String> {

        public Text() {
            super(String.class);
        }

        @Override
        public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.currentToken() == JsonToken.VALUE_STRING) {
                return p.getText();
            }
            return (String) ctxt.handleUnexpectedToken(String.class, p);
        }
    }

    /** Only a JSON integer number that fits an {@code int}. */
    public static class WholeNumber extends StdScalarDeserializer<Integer> {

        public WholeNumber() {
            super(Integer.class);
        }

        @Override
        public Integer deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.currentToken() == JsonToken.VALUE_NUMBER_INT && p.getNumberType() == JsonParser.NumberType.INT) {
                return p.getIntValue();
            }
            return (Integer) ctxt.handleUnexpectedToken(Integer.class, p);
        }
    }

    /** Only JSON {@code true} or {@code false}. */
    public static class Flag extends StdScalarDeserializer<Boolean> {

        public Flag() {
            super(Boolean.class);
        }

        @Override
        public Boolean deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonToken token = p.currentToken();
            if (token == JsonToken.VALUE_TRUE) {
                return Boolean.TRUE;
            }
            if (token == JsonToken.VALUE_FALSE) {
                return Boolean.FALSE;
            }
            return (Boolean) ctxt.handleUnexpectedToken(Boolean.class, p);
        }
    }

    /**
     * A clinic wall-clock date-time string {@code yyyy-MM-dd'T'HH:mm} or {@code yyyy-MM-dd'T'HH:mm:ss} (an optional
     * fraction of a second is tolerated), with no offset and no {@code Z}.
     */
    public static class ClinicDateTime extends StdScalarDeserializer<LocalDateTime> {

        private static final Pattern FORMAT =
                Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?$");

        public ClinicDateTime() {
            super(LocalDateTime.class);
        }

        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.currentToken() == JsonToken.VALUE_STRING) {
                String text = p.getText().trim();
                if (FORMAT.matcher(text).matches()) {
                    try {
                        return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                    } catch (DateTimeParseException e) {
                        return (LocalDateTime) ctxt.handleWeirdStringValue(LocalDateTime.class, text,
                                "not a valid date-time");
                    }
                }
                return (LocalDateTime) ctxt.handleWeirdStringValue(LocalDateTime.class, text,
                        "expected yyyy-MM-dd'T'HH:mm[:ss] without an offset");
            }
            return (LocalDateTime) ctxt.handleUnexpectedToken(LocalDateTime.class, p);
        }
    }
}
