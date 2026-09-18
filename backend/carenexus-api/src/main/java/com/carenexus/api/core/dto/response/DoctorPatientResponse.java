package com.carenexus.api.core.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DoctorPatientResponse {

    @JsonProperty("patientId")
    private Integer patientId;

    @JsonProperty("userId")
    private Integer userId;

    @JsonProperty("firstName")
    private String firstName;

    @JsonProperty("lastName")
    private String lastName;

    @JsonProperty("email")
    private String email;

    @JsonProperty("phone")
    private String phone;

    @JsonProperty("dateOfBirth")
    private LocalDate dateOfBirth;

    @JsonProperty("gender")
    private String gender;

    @JsonProperty("bloodType")
    private String bloodType;

    @JsonProperty("allergies")
    private String allergies;

    @JsonProperty("chronicConditions")
    private String chronicConditions;

    @JsonProperty("healthStatus")
    private String healthStatus;

    @JsonProperty("totalAppointments")
    private Integer totalAppointments;

    @JsonProperty("lastAppointmentDate")
    private LocalDateTime lastAppointmentDate;

    @JsonProperty("upcomingAppointmentCount")
    private Integer upcomingAppointmentCount;

    @JsonProperty("completedAppointmentCount")
    private Integer completedAppointmentCount;

    @JsonProperty("cancelledAppointmentCount")
    private Integer cancelledAppointmentCount;

    @JsonProperty("noShowCount")
    private Integer noShowCount;

    @JsonProperty("rescheduledCount")
    private Integer rescheduledCount;

    @JsonProperty("recentAppointments")
    private List<AppointmentResponse> recentAppointments;

    @JsonProperty("patientCreatedAt")
    private LocalDateTime patientCreatedAt;
}
