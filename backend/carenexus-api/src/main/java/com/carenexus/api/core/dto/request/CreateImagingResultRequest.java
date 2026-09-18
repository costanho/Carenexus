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

    @JsonProperty("patientId")
    private Integer patientId;

    @JsonProperty("doctorId")
    private Integer doctorId;

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

    @JsonProperty("reviewedBy")
    private Integer reviewedBy;
}
