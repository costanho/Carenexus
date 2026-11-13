package com.carenexus.api.common.exception;

import com.carenexus.api.auth.service.TooManyLoginAttemptsException;
import com.carenexus.api.common.dto.ValidationPatterns;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Status / message mapping of GlobalExceptionHandler (standalone MockMvc, no database).
 */
public class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @RestController
    @RequestMapping("/t")
    static class ThrowingController {
        @GetMapping("/plain")
        public void plain() { throw new RuntimeException("Appointment time is in the past"); }

        @GetMapping("/plain-not-found")
        public void plainNotFound() { throw new RuntimeException("Lab result not found"); }

        @GetMapping("/not-found")
        public void notFound() { throw new ResourceNotFoundException("Consultation not found"); }

        @GetMapping("/conflict")
        public void conflict() { throw new ConflictException("This time slot is already booked"); }

        @GetMapping("/forbidden")
        public void forbidden() { throw new AuthorizationException("Patients can only access their own data", "MEDICAL_RECORD", 3); }

        @GetMapping("/iae")
        public void iae() { throw new IllegalArgumentException("Invalid status"); }

        @GetMapping("/npe")
        public void npe() { throw new NullPointerException("secret internal detail"); }

        @GetMapping("/sql")
        public void sql() {
            throw new InvalidDataAccessResourceUsageException("ERROR: column c.care_team_id does not exist");
        }

        @GetMapping("/dup-phone")
        public void dupPhone() {
            throw new DataIntegrityViolationException("could not execute statement [insert into users ...]",
                    new ConstraintViolationException("duplicate key", new SQLException("Key (phone)=(+1) already exists"), "users_phone_key"));
        }

        @GetMapping("/fk")
        public void fk() {
            throw new DataIntegrityViolationException("insert or update on table violates foreign key",
                    new ConstraintViolationException("fk", new SQLException("detail"), "appointments_doctor_profile_fkey"));
        }

        @GetMapping("/wrapped")
        public void wrapped() { throw new RuntimeException(new IllegalStateException("internal")); }

        @GetMapping("/typed/{id}")
        public void typed(@PathVariable Integer id) { }

        @GetMapping("/param")
        public void param(@RequestParam String q) { }

        @GetMapping("/attr")
        public void attr(@RequestAttribute("systemId") String systemId) { }

        @PostMapping("/body")
        public void body(@RequestBody Map<String, Object> body) { }

        @GetMapping("/bad-request")
        public void badRequest() { throw new BadRequestException("Appointment time is in the past"); }

        @GetMapping("/bad-request-code")
        public void badRequestCode() { throw new BadRequestException("FILE_REJECTED", "This file type is not allowed"); }

        @GetMapping("/conflict-code")
        public void conflictCode() {
            throw new ConflictException("IDEMPOTENCY_IN_PROGRESS", "This request is already being processed. Please wait a moment.");
        }

        @GetMapping("/api-forbidden")
        public void apiForbidden() {
            throw new ApiException(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED", "Verify your email address to continue");
        }

        @GetMapping("/api-unavailable")
        public void apiUnavailable() {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_UNAVAILABLE", "Payments are temporarily unavailable");
        }

        @GetMapping("/rate-limited")
        public void rateLimited() { throw new TooManyRequestsException("Too many requests. Please try again in 2 minutes.", 93); }

        @GetMapping("/login-throttled")
        public void loginThrottled() {
            throw new TooManyLoginAttemptsException("Too many failed sign-in attempts. Please wait 15 minutes and try again.");
        }

        @PostMapping("/valid")
        public void valid(@Valid @RequestBody ValidatedBody body) { }

        @PostMapping("/valid-long")
        public void validLong(@Valid @RequestBody LongMessageBody body) { }

        @GetMapping("/valid-param")
        public void validParam(@RequestParam @Min(value = 1, message = "must be at least 1") int size) { }

        @GetMapping("/constraint")
        public void constraint() {
            throw new jakarta.validation.ConstraintViolationException("patientSystemId: invalid", Set.of());
        }
    }

    static class LongMessageBody {
        static final String LONG_MESSAGE = "is required and must describe the reason for the visit in enough detail for "
                + "the clinician to prepare, including any symptoms and how long they have lasted";

        @NotBlank(message = LONG_MESSAGE)
        public String note;
    }

    /** Request body with constraints, as request DTOs carry them. */
    static class ValidatedBody {
        @NotBlank(message = "is required")
        public String reason;

        @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = "must be a system id such as PAT-00001")
        public String patientSystemId;

        @Size(max = 5, message = "must be at most 5 characters")
        public String code;
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void plainRuntimeException_echoesMessageAs400() throws Exception {
        mockMvc.perform(get("/t/plain"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Appointment time is in the past"));
    }

    @Test
    void plainRuntimeExceptionNotFound_is404() throws Exception {
        mockMvc.perform(get("/t/plain-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Lab result not found"));
    }

    @Test
    void ownExceptions() throws Exception {
        mockMvc.perform(get("/t/not-found")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Consultation not found"));
        mockMvc.perform(get("/t/conflict")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This time slot is already booked"));
        mockMvc.perform(get("/t/forbidden")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Patients can only access their own data"))
                .andExpect(jsonPath("$.resourceType").value("MEDICAL_RECORD"));
        mockMvc.perform(get("/t/iae")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid status"));
    }

    @Test
    void internalExceptions_areGeneric500() throws Exception {
        for (String path : new String[]{"/t/npe", "/t/sql", "/t/wrapped"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message").value(GlobalExceptionHandler.GENERIC_ERROR));
        }
    }

    @Test
    void dataIntegrity_is409WithSafeMessage() throws Exception {
        mockMvc.perform(get("/t/dup-phone"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("An account with this phone number already exists"));
        mockMvc.perform(get("/t/fk"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Conflicting or invalid data"));
    }

    @Test
    void framework4xx() throws Exception {
        mockMvc.perform(get("/t/typed/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'id'"));
        mockMvc.perform(get("/t/param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing required parameter 'q'"));
        mockMvc.perform(get("/t/attr"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication required"));
        mockMvc.perform(post("/t/body").contentType(MediaType.APPLICATION_JSON).content("{bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"));
        mockMvc.perform(delete("/t/plain"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.message").value("Method not allowed"));
        mockMvc.perform(post("/t/body").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
    }

    // ==================== TYPED EXCEPTIONS, CODES, RATE LIMITS ====================

    @Test
    void badRequestException_is400_withOptionalCode() throws Exception {
        mockMvc.perform(get("/t/bad-request"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Appointment time is in the past"))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.errors").doesNotExist());
        mockMvc.perform(get("/t/bad-request-code"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_REJECTED"))
                .andExpect(jsonPath("$.message").value("This file type is not allowed"));
    }

    @Test
    void conflictException_carriesItsCode() throws Exception {
        mockMvc.perform(get("/t/conflict-code"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_IN_PROGRESS"));
        mockMvc.perform(get("/t/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    void apiException_usesItsStatusCodeAndMessage() throws Exception {
        mockMvc.perform(get("/t/api-forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"))
                .andExpect(jsonPath("$.message").value("Verify your email address to continue"));
        mockMvc.perform(get("/t/api-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"));
    }

    @Test
    void tooManyRequests_is429_withRetryAfterAndCode() throws Exception {
        mockMvc.perform(get("/t/rate-limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "93"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("Too many requests. Please try again in 2 minutes."));
    }

    @Test
    void loginThrottle_isATooManyRequests_withTheLoginWindowAsRetryAfter() throws Exception {
        mockMvc.perform(get("/t/login-throttled"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "900"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value(containsString("Too many failed sign-in attempts")));
    }

    // ==================== BEAN VALIDATION ====================

    @Test
    void invalidBody_is400_withFirstFieldMessageCodeAndEveryFieldError() throws Exception {
        mockMvc.perform(post("/t/valid").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\" \",\"patientSystemId\":\"PAT-1\",\"code\":\"ABCDEFG\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                // fields are reported in alphabetical order, so the message is stable
                .andExpect(jsonPath("$.message").value("code: must be at most 5 characters"))
                .andExpect(jsonPath("$.errors", hasSize(3)))
                .andExpect(jsonPath("$.errors[0].field").value("code"))
                .andExpect(jsonPath("$.errors[1].field").value("patientSystemId"))
                .andExpect(jsonPath("$.errors[1].message").value("must be a system id such as PAT-00001"))
                .andExpect(jsonPath("$.errors[2].field").value("reason"))
                .andExpect(jsonPath("$.errors[2].message").value("is required"));
    }

    @Test
    void validBody_passes() throws Exception {
        mockMvc.perform(post("/t/valid").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"check-up\",\"patientSystemId\":\"PAT-00001\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void constrainedRequestParam_is400_withTheParameterAsField() throws Exception {
        mockMvc.perform(get("/t/valid-param").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("size: must be at least 1"))
                .andExpect(jsonPath("$.errors[0].field").value("size"));
    }

    @Test
    void constraintViolationException_is400ValidationFailed() throws Exception {
        mockMvc.perform(get("/t/constraint"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Validation failed"));
    }

    // ==================== ENVELOPE ====================

    @Test
    void envelope_hasIsoInstantTimestampStatusErrorAndPath() throws Exception {
        String timestamp = com.jayway.jsonpath.JsonPath.read(
                mockMvc.perform(get("/t/not-found"))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.status").value(404))
                        .andExpect(jsonPath("$.error").value("Not Found"))
                        .andExpect(jsonPath("$.path").value("/t/not-found"))
                        .andExpect(jsonPath("$.timestamp").value(endsWith("Z")))
                        .andExpect(jsonPath("$.requestId").doesNotExist())
                        .andExpect(jsonPath("$.resourceType").doesNotExist())
                        .andReturn().getResponse().getContentAsString(), "$.timestamp");
        assertDoesNotThrow(() -> Instant.parse(timestamp));
    }

    @Test
    void validationMessage_isClippedTo120Characters_butErrorsKeepTheFullText() throws Exception {
        String body = mockMvc.perform(post("/t/valid-long").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value(LongMessageBody.LONG_MESSAGE))
                .andReturn().getResponse().getContentAsString();
        String message = com.jayway.jsonpath.JsonPath.read(body, "$.message");
        org.junit.jupiter.api.Assertions.assertTrue(message.length() <= GlobalExceptionHandler.MAX_MESSAGE_LENGTH, message);
        org.junit.jupiter.api.Assertions.assertTrue(message.startsWith("note: "), message);
    }
}
