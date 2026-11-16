package com.carenexus.api.corrections.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A doctor an administrator may assign a correction request to: an active doctor related to the patient.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "A doctor a correction request can be assigned to")
public class AssignableDoctorResponse {

    @Schema(description = "Value to send as doctorSystemId to the assign endpoint", example = "DOC-00002")
    private String doctorSystemId;

    @Schema(description = "\"<first> <last>\" (no title prefix)", example = "Farai Ndlovu")
    private String doctorName;

    @Schema(description = "The doctor's specialization; null when not set", nullable = true, example = "General practice")
    private String specialization;

    @Schema(description = "True for the request's current assignee", example = "true")
    @JsonProperty("currentAssignee")
    private boolean currentAssignee;
}
