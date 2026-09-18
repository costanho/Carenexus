package com.carenexus.api.core.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DoctorDashboardResponse {

    @JsonProperty("doctorId")
    private Integer doctorId;

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

    @JsonProperty("specialization")
    private String specialization;

    @JsonProperty("licenseNo")
    private String licenseNo;

    @JsonProperty("bio")
    private String bio;

    @JsonProperty("isActive")
    private Boolean isActive;

    @JsonProperty("totalPatients")
    private Integer totalPatients;

    @JsonProperty("totalAppointments")
    private Integer totalAppointments;

    @JsonProperty("upcomingAppointments")
    private Integer upcomingAppointments;

    @JsonProperty("completedConsultations")
    private Integer completedConsultations;

    @JsonProperty("pendingConsultations")
    private Integer pendingConsultations;

    @JsonProperty("todaySchedule")
    private List<ScheduleEntryResponse> todaySchedule;

    @JsonProperty("upcomingSchedule")
    private List<ScheduleEntryResponse> upcomingSchedule;
}
