package com.carenexus.api.core.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One patient of the calling doctor. Every appointment-derived value is computed from the appointments between
 * this doctor and the patient only. Date-times of appointments are clinic wall-clock times without offset.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A patient of the calling doctor")
public class DoctorPatientResponse {

    @JsonProperty("patientSystemId")
    @Schema(description = "Patient system id", example = "PAT-00001")
    private String patientSystemId;

    @JsonProperty("userSystemId")
    @Schema(description = "Same value as patientSystemId", example = "PAT-00001")
    private String userSystemId;

    @JsonProperty("firstName")
    private String firstName;

    @JsonProperty("lastName")
    private String lastName;

    @JsonProperty("email")
    @Schema(description = "Sign-in email; null for a managed dependent without one")
    private String email;

    @JsonProperty("phone")
    private String phone;

    @JsonProperty("dateOfBirth")
    private LocalDate dateOfBirth;

    @JsonProperty("gender")
    @Schema(description = "MALE, FEMALE or OTHER", nullable = true)
    private String gender;

    @JsonProperty("bloodType")
    private String bloodType;

    @JsonProperty("allergies")
    @Schema(description = "A string holding a JSON array of strings")
    private String allergies;

    @JsonProperty("chronicConditions")
    @Schema(description = "A string holding a JSON array of strings")
    private String chronicConditions;

    @JsonProperty("healthStatus")
    @Schema(description = "STABLE, MONITOR or CRITICAL")
    private String healthStatus;

    @JsonProperty("healthStatusNote")
    @Schema(description = "Note given with the latest health-status change; filled by the detail endpoint only")
    private String healthStatusNote;

    @JsonProperty("healthStatusUpdatedAt")
    @Schema(description = "When the status or note was last set by a doctor or administrator; detail endpoint only")
    private LocalDateTime healthStatusUpdatedAt;

    @JsonProperty("healthStatusUpdatedBySystemId")
    @Schema(description = "Doctor or administrator who last set it; detail endpoint only")
    private String healthStatusUpdatedBySystemId;

    @JsonProperty("healthStatusUpdatedByName")
    @Schema(description = "\"Dr. First Last\" for a doctor, \"First Last\" for an administrator; detail endpoint only")
    private String healthStatusUpdatedByName;

    @JsonProperty("totalAppointments")
    @Schema(description = "Appointments with this doctor in any status")
    private Integer totalAppointments;

    @JsonProperty("lastAppointmentDate")
    @Schema(description = "Deprecated alias of lastVisitAt", deprecated = true)
    private LocalDateTime lastAppointmentDate;

    @JsonProperty("upcomingAppointmentCount")
    @Schema(description = "SCHEDULED or RESCHEDULED appointments starting at or after clinic now")
    private Integer upcomingAppointmentCount;

    @JsonProperty("completedAppointmentCount")
    private Integer completedAppointmentCount;

    @JsonProperty("cancelledAppointmentCount")
    private Integer cancelledAppointmentCount;

    @JsonProperty("noShowCount")
    private Integer noShowCount;

    @JsonProperty("rescheduledCount")
    private Integer rescheduledCount;

    @JsonProperty("completedVisits")
    @Schema(description = "Same number as completedAppointmentCount")
    private Integer completedVisits;

    @JsonProperty("nextAppointmentAt")
    @Schema(description = "Start of the earliest SCHEDULED or RESCHEDULED appointment at or after clinic now")
    private LocalDateTime nextAppointmentAt;

    @JsonProperty("nextAppointmentId")
    private Integer nextAppointmentId;

    @JsonProperty("nextAppointmentType")
    @Schema(description = "IN_PERSON, VIDEO or PHONE")
    private String nextAppointmentType;

    @JsonProperty("lastVisitAt")
    @Schema(description = "Latest COMPLETED appointment not after clinic now")
    private LocalDateTime lastVisitAt;

    @JsonProperty("recentAppointments")
    @Schema(description = "Five most recent appointments with this doctor (legacy list and detail); null in search rows. "
            + "doctorName, facilityName, facilityAddress and contactPhone are not filled here")
    private List<AppointmentResponse> recentAppointments;

    @JsonProperty("patientCreatedAt")
    private LocalDateTime patientCreatedAt;
}
