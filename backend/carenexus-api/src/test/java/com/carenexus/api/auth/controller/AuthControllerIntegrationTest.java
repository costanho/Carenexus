package com.carenexus.api.auth.controller;

import com.carenexus.api.BaseIntegrationTest;
import com.carenexus.api.auth.dto.request.LoginRequest;
import com.carenexus.api.auth.model.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class AuthControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    private User testPatient;

    @BeforeEach
    public void setUp() {
        // Create test patient
        testPatient = new User();
        testPatient.setFirstName("Linda");
        testPatient.setLastName("Davis");
        testPatient.setEmail("linda@example.com");
        testPatient.setPhone("+1234567890");
        testPatient.setPasswordHash(passwordEncoder.encode("password123"));
        testPatient.setRole("PATIENT");
        testPatient.setIsActive(true);
        testPatient.setCreatedAt(java.time.LocalDateTime.now());
        testPatient.setUpdatedAt(java.time.LocalDateTime.now());
        testPatient = userRepository.save(testPatient);
    }

    @Test
    public void testLogin_Success() throws Exception {
        LoginRequest request = LoginRequest.builder()
                .email("linda@example.com")
                .password("password123")
                .deviceType("iOS")
                .deviceInfo("iPhone 14")
                .build();

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andExpect(jsonPath("$.refreshToken", notNullValue()))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.userId").value(testPatient.getUserId()))
                .andExpect(jsonPath("$.firstName").value("Linda"))
                .andExpect(jsonPath("$.role").value("PATIENT"));
    }

    @Test
    public void testLogin_InvalidPassword() throws Exception {
        LoginRequest request = LoginRequest.builder()
                .email("linda@example.com")
                .password("wrongpassword")
                .deviceType("iOS")
                .deviceInfo("iPhone 14")
                .build();

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void testLogin_InvalidEmail() throws Exception {
        LoginRequest request = LoginRequest.builder()
                .email("nonexistent@example.com")
                .password("password123")
                .deviceType("iOS")
                .deviceInfo("iPhone 14")
                .build();

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void testLogin_InactiveAccount() throws Exception {
        testPatient.setIsActive(false);
        userRepository.save(testPatient);

        LoginRequest request = LoginRequest.builder()
                .email("linda@example.com")
                .password("password123")
                .deviceType("iOS")
                .deviceInfo("iPhone 14")
                .build();

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void testRefreshToken_Success() throws Exception {
        // First login to get refresh token
        LoginRequest loginRequest = LoginRequest.builder()
                .email("linda@example.com")
                .password("password123")
                .deviceType("iOS")
                .deviceInfo("iPhone 14")
                .build();

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = loginResult.getResponse().getContentAsString();
        String refreshToken = objectMapper.readTree(responseBody).get("refreshToken").asText();

        // Now test refresh
        String refreshRequestBody = objectMapper.writeValueAsString(
                java.util.Map.of("refreshToken", refreshToken));

        mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshRequestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    public void testRefreshToken_InvalidToken() throws Exception {
        String refreshRequestBody = objectMapper.writeValueAsString(
                java.util.Map.of("refreshToken", "invalid-token"));

        mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshRequestBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void testLogout_Success() throws Exception {
        String token = generateToken(testPatient.getUserId(), "linda@example.com", "PATIENT");

        mockMvc.perform(post("/api/auth/logout")
                .header("Authorization", bearerToken(token)))
                .andExpect(status().isOk());
    }
}
