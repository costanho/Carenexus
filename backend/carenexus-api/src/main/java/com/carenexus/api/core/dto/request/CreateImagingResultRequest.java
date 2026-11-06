package com.carenexus.api.core.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateImagingResultRequest {

    @JsonProperty("patientSystemId")
    private String patientSystemId;

    /** Ignored by the server: the author doctor is always the authenticated caller. Kept for compatibility. */
    @JsonProperty("doctorSystemId")
    private String doctorSystemId;

    @JsonProperty("consultationId")
    private Integer consultationId;

    @JsonProperty("imagingType")
    private String imagingType;

    @JsonProperty("bodyPart")
    private String bodyPart;

    @JsonProperty("radiologistReport")
    private String radiologistReport;

    @JsonProperty("findings")
    private String findings;

    @JsonProperty("impression")
    private String impression;

    @JsonProperty("fileUrl")
    private String fileUrl;

    @JsonProperty("thumbnailUrl")
    private String thumbnailUrl;

    @JsonProperty("status")
    private String status;

    @JsonProperty("imageDate")
    private LocalDate imageDate;

    /** Ignored by the server: reviews are recorded via POST /{id}/review as the authenticated doctor. */
    @JsonProperty("reviewedBy")
    private String reviewedBy;
}
