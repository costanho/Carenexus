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
    private String role; // patient, doctor or proxy/caregiver (ADMIN is rejected; doctors start pending verification)
    private String caregiverSystemId; // optional: links new patient to a caregiver
    private String relationship;      // optional: e.g. "son", "mother", "guardian"
}
