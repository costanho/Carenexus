package com.carenexus.api.auth.dto.request;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegisterRequest {
    // User table fields only
    private String firstName;
    private String lastName;
    private String email;
    private String password;
    private String phone;
    private String role; // PATIENT, DOCTOR, CAREGIVER, ADMIN
}
