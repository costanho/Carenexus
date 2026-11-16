package com.carenexus.api.facility;

import com.carenexus.api.facility.dto.request.FacilityRequest;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Normalisation and Bean Validation of {@link FacilityRequest} without a Spring context: trimming, blank optional
 * fields becoming null, the pinned messages and the phone / email rules.
 */
class FacilityRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;
    /** Configured like Spring Boot's mapper: unknown fields are ignored. */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private List<String> violations(FacilityRequest request) {
        Set<ConstraintViolation<FacilityRequest>> found = validator.validate(request);
        return found.stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .sorted()
                .toList();
    }

    @Test
    void jsonBody_isTrimmed_andBlankOptionalFieldsBecomeNull() throws Exception {
        FacilityRequest request = objectMapper.readValue(
                "{\"name\":\"  Avondale  Clinic \",\"type\":\"\",\"address\":\"   \",\"phone\":\" +263 24 270 0000 \","
                        + "\"email\":\" Desk@Clinic.Example \",\"isActive\":false,\"facilityId\":4}",
                FacilityRequest.class);
        assertEquals("Avondale  Clinic", request.name(), "inner whitespace is kept");
        assertNull(request.type());
        assertNull(request.address());
        assertEquals("+263 24 270 0000", request.phone());
        assertEquals("Desk@Clinic.Example", request.email(), "case is kept");
        assertEquals(List.of(), violations(request));
    }

    @Test
    void blankOrMissingName_failsNotBlank() {
        assertEquals(List.of("name: must not be blank"), violations(new FacilityRequest(null, null, null, null, null)));
        assertEquals(List.of("name: must not be blank"), violations(new FacilityRequest("   ", null, null, null, null)));
    }

    @Test
    void lengthsArePinnedAndMeasuredAfterTrimming() {
        assertEquals(List.of(), violations(new FacilityRequest(" " + "n".repeat(200) + " ", "t".repeat(80),
                "a".repeat(300), null, null)));
        assertEquals(List.of(
                        "address: must be at most 300 characters",
                        "name: must be at most 200 characters",
                        "type: must be at most 80 characters"),
                violations(new FacilityRequest("n".repeat(201), "t".repeat(81), "a".repeat(301), null, null)));
    }

    @Test
    void phone_mustMatchThePhonePattern() {
        for (String valid : List.of("+263 24 270 0000", "(024) 270-0000", "0772.123.456", "1234567")) {
            assertEquals(List.of(), violations(new FacilityRequest("Clinic", null, null, valid, null)), valid);
        }
        for (String invalid : List.of("123456", "+263-4-7XXXXX", "call us")) {
            assertEquals(List.of("phone: must be a valid phone number"),
                    violations(new FacilityRequest("Clinic", null, null, invalid, null)), invalid);
        }
        assertEquals(List.of("phone: must be a valid phone number", "phone: must be at most 20 characters"),
                violations(new FacilityRequest("Clinic", null, null, "+263 24 270 0000 000 000", null)));
    }

    @Test
    void email_mustBeAnAddressWithADottedDomain_andGivesOneMessage() {
        assertEquals(List.of(), violations(new FacilityRequest("Clinic", null, null, null, "a@b.co")));
        for (String invalid : List.of("front desk", "e@x", "@clinic.example", "desk@", "a b@c.de")) {
            assertEquals(List.of("email: must be a valid email address"),
                    violations(new FacilityRequest("Clinic", null, null, null, invalid)), invalid);
        }
        String tooLong = "a".repeat(62) + "@" + "b".repeat(40) + "." + "c".repeat(40) + ".example";
        assertEquals(List.of("email: must be at most 150 characters"),
                violations(new FacilityRequest("Clinic", null, null, null, tooLong)));
    }
}
