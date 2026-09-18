package com.carenexus.api.core.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "imaging_results")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImagingResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "imaging_id")
    private Integer imagingId;

    @Column(name = "patient_id", nullable = false)
    private Integer patientId;

    @Column(name = "doctor_id", nullable = false)
    private Integer doctorId;

    @Column(name = "consultation_id")
    private Integer consultationId;

    @Column(name = "imaging_type", nullable = false, length = 20)
    private String imagingType;

    @Column(name = "body_part", columnDefinition = "TEXT")
    private String bodyPart;

    @Column(name = "radiologist_report", columnDefinition = "TEXT")
    private String radiologistReport;

    @Column(name = "findings", columnDefinition = "TEXT")
    private String findings;

    @Column(name = "impression", columnDefinition = "TEXT")
    private String impression;

    @Column(name = "file_url", length = 500)
    private String fileUrl;

    @Column(name = "thumbnail_url", length = 500)
    private String thumbnailUrl;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "image_date", nullable = false)
    private LocalDate imageDate;

    @Column(name = "reviewed_by")
    private Integer reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (imageDate == null) {
            imageDate = LocalDate.now();
        }
    }
}
