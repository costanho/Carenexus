package com.carenexus.api.medication.dto.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;

import java.io.IOException;

/**
 * Accepts only JSON strings. Jackson would otherwise turn a number or boolean into its text, so {@code [8]} would
 * be reported as an invalid time instead of a malformed body. JSON null is handled by Jackson before this runs
 * (null element) and is reported by Bean Validation.
 */
public class StrictStringDeserializer extends StdScalarDeserializer<String> {

    public StrictStringDeserializer() {
        super(String.class);
    }

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_STRING) {
            return parser.getText();
        }
        return (String) context.handleUnexpectedToken(String.class, parser);
    }
}
