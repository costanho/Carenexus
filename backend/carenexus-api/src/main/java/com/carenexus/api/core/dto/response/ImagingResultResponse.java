package com.carenexus.api.core.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImagingResultResponse {

    @JsonProperty("imagingId")
    private Integer imagingId;

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

    @JsonProperty("reviewedAt")
    private LocalDateTime reviewedAt;

    @JsonProperty("createdAt")
    private LocalDateTime createdAt;
}
