package com.carenexus.api.auth.dto;

import com.carenexus.api.auth.dto.request.LoginRequest;
import com.carenexus.api.auth.dto.request.RegisterRequest;
import com.carenexus.api.auth.dto.request.TokenRefreshRequest;
import com.carenexus.api.auth.dto.validation.PasswordPolicy;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The auth request DTOs as Jackson and Bean Validation see them: text is trimmed when it is bound, a blank optional
 * value counts as absent, passwords and tokens are kept exactly as sent, and every rule reports one message.
 */
class AuthRequestDtoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    private static <T> List<String> violations(T request) {
        Set<ConstraintViolation<T>> found = validator.validate(request);
        return found.stream().map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted().toList();
    }

    @Test
    void registerRequest_isNormalisedWhenBound() throws Exception {
        RegisterRequest request = MAPPER.readValue("""
                {"firstName": "  Tendai ", "lastName": "Moyo\\t", "email": "  Tendai.Moyo@Example.COM ",
                 "password": "  Str0ng Passw0rd  ", "role": " doctor ", "phone": "   ", "caregiverSystemId": "",
                 "relationship": " ", "licenseNo": " MDPCZ-1 ", "specialization": " Cardiology ",
                 "licensingBody": "", "licenseCountry": " zw ", "acceptedTermsVersion": " 1 ",
                 "acceptedPrivacyVersion": "", "humanVerificationToken": "  "}
                """, RegisterRequest.class);

        assertThat(request.getFirstName()).isEqualTo("Tendai");
        assertThat(request.getLastName()).isEqualTo("Moyo");
        assertThat(request.getEmail()).isEqualTo("tendai.moyo@example.com");
        assertThat(request.getPassword()).as("a password is never trimmed").isEqualTo("  Str0ng Passw0rd  ");
        assertThat(request.getRole()).isEqualTo("doctor");
        assertThat(request.getPhone()).isNull();
        assertThat(request.getCaregiverSystemId()).isNull();
        assertThat(request.getRelationship()).isNull();
        assertThat(request.getLicenseNo()).as("case kept").isEqualTo("MDPCZ-1");
        assertThat(request.getSpecialization()).isEqualTo("Cardiology");
        assertThat(request.getLicensingBody()).isNull();
        assertThat(request.getLicenseCountry()).isEqualTo("ZW");
        assertThat(request.getAcceptedTermsVersion()).isEqualTo("1");
        assertThat(request.getAcceptedPrivacyVersion()).isNull();
        assertThat(request.getHumanVerificationToken()).isNull();
        assertThat(violations(request)).isEmpty();
    }

    @Test
    void registerRequest_minimalBodyOfTheNativeScreen_isValid() throws Exception {
        RegisterRequest request = MAPPER.readValue("""
                {"firstName": "Rudo", "lastName": "Sithole", "email": "rudo@example.com", "phone": "+263 77 123 4567",
                 "password": "Passw0rd", "role": "PATIENT"}
                """, RegisterRequest.class);

        assertThat(violations(request)).isEmpty();
    }

    @Test
    void registerRequest_blankRequiredFields_reportOnlyThatTheyAreRequired() throws Exception {
        RegisterRequest request = MAPPER.readValue("""
                {"firstName": " ", "lastName": "", "email": "   ", "password": "", "role": " "}
                """, RegisterRequest.class);

        assertThat(violations(request)).containsExactly(
                "email: Email is required",
                "firstName: First name is required",
                "lastName: Last name is required",
                "password: Password is required",
                "role: Role is required");
    }

    @Test
    void registerRequest_emailRules() {
        for (String valid : List.of("a@b.co", "first.last+tag@sub.example.org", "x".repeat(138) + "@example.com")) {
            RegisterRequest request = validRegistration();
            request.setEmail(valid);
            assertThat(violations(request)).as(valid).isEmpty();
        }
        for (String invalid : List.of("plain", "no-domain@", "@no-local.com", "two@@example.com", "space in@example.com",
                "no-dot@example", "short-tld@example.c", "x".repeat(139) + "@example.com")) {
            RegisterRequest request = validRegistration();
            request.setEmail(invalid);
            assertThat(violations(request)).as(invalid).containsExactly("email: Please enter a valid email address");
        }
    }

    @Test
    void passwordPolicy_reportsTheFirstRuleThatFails() {
        assertThat(PasswordPolicy.violation(null)).isEqualTo("Password is required");
        assertThat(PasswordPolicy.violation("")).isEqualTo("Password is required");
        assertThat(PasswordPolicy.violation("        ")).isEqualTo("Password is required");
        String weak = "Password must be at least 8 characters and contain at least one letter and one number";
        assertThat(PasswordPolicy.violation("Sh0rt")).isEqualTo(weak);
        assertThat(PasswordPolicy.violation("lettersonly")).isEqualTo(weak);
        assertThat(PasswordPolicy.violation("12345678")).isEqualTo(weak);
        assertThat(PasswordPolicy.violation("pässwörd1")).as("letters of any alphabet count").isNull();
        assertThat(PasswordPolicy.violation("Passw0rd")).isNull();
        assertThat(PasswordPolicy.violation("P4" + "x".repeat(70))).as("72 bytes").isNull();
        assertThat(PasswordPolicy.violation("P4" + "x".repeat(71))).isEqualTo("Password must be at most 72 characters");
        // 72 is a limit in bytes: 37 two-byte letters and a digit are 75 bytes.
        assertThat(PasswordPolicy.violation("ä".repeat(37) + "1")).isEqualTo("Password must be at most 72 characters");
    }

    @Test
    void loginRequest_emailIsNormalised_passwordAndUnknownPropertiesAreLeftAlone() throws Exception {
        ObjectMapper lenient = new ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        LoginRequest request = lenient.readValue("""
                {"email": "  Linda@Example.com ", "password": " keep my spaces ", "role": "patient",
                 "deviceType": " Web ", "deviceInfo": "  "}
                """, LoginRequest.class);

        assertThat(request.getEmail()).isEqualTo("linda@example.com");
        assertThat(request.getPassword()).isEqualTo(" keep my spaces ");
        assertThat(request.getDeviceType()).isEqualTo("Web");
        assertThat(request.getDeviceInfo()).isNull();
        assertThat(violations(request)).isEmpty();
    }

    @Test
    void loginRequest_rules() {
        LoginRequest empty = new LoginRequest();
        assertThat(violations(empty)).containsExactly("email: Email is required", "password: Password is required");

        LoginRequest oversized = LoginRequest.builder()
                .email("x".repeat(139) + "@example.com")
                .password("p".repeat(201))
                .deviceType("d".repeat(51))
                .deviceInfo("i".repeat(256))
                .build();
        assertThat(violations(oversized)).containsExactly(
                "deviceInfo: must be at most 255 characters",
                "deviceType: must be at most 50 characters",
                "email: must be at most 150 characters",
                "password: must be at most 200 characters");

        // The email format is not checked at sign-in: an unknown address is simply a 401.
        LoginRequest odd = LoginRequest.builder().email("not-an-email").password(" ").build();
        assertThat(violations(odd)).isEmpty();
    }

    @Test
    void tokenRefreshRequest_tokenIsKeptAsSent_andLimitedTo512Characters() throws Exception {
        TokenRefreshRequest request = MAPPER.readValue("{\"refreshToken\": \"  abc  \"}", TokenRefreshRequest.class);
        assertThat(request.getRefreshToken()).isEqualTo("  abc  ");
        assertThat(violations(request)).isEmpty();
        assertThat(violations(new TokenRefreshRequest())).as("a missing token is not a validation error").isEmpty();
        assertThat(violations(new TokenRefreshRequest("x".repeat(512)))).isEmpty();
        assertThat(violations(new TokenRefreshRequest("x".repeat(513))))
                .containsExactly("refreshToken: must be at most 512 characters");
    }

    private static RegisterRequest validRegistration() {
        RegisterRequest request = new RegisterRequest();
        request.setFirstName("Tendai");
        request.setLastName("Moyo");
        request.setEmail("tendai@example.com");
        request.setPassword("Str0ng-Passw0rd");
        request.setRole("patient");
        return request;
    }
}
