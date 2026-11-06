package com.carenexus.api.common.exception;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import java.sql.SQLException;
import java.util.Map;

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
}
