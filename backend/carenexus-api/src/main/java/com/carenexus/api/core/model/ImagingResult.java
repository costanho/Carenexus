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

    public static final String STATUS_PENDING = LabResult.STATUS_PENDING;
    public static final String STATUS_REQUIRES_REVIEW = LabResult.STATUS_REQUIRES_REVIEW;
    public static final String STATUS_COMPLETED = LabResult.STATUS_COMPLETED;
    public static final String STATUS_CRITICAL = LabResult.STATUS_CRITICAL;
    public static final String STATUS_CANCELLED = LabResult.STATUS_CANCELLED;

    /** The imaging_results.imaging_type CHECK. */
    public static final java.util.List<String> IMAGING_TYPES = java.util.List.of(
            "XRAY", "MRI", "CT_SCAN", "ULTRASOUND", "PET", "MAMMOGRAPHY", "DEXA", "ECHO");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "imaging_id")
    private Integer imagingId;

    @Column(name = "patient_system_id", nullable = false, length = 20)
    private String patientSystemId;

    @Column(name = "doctor_system_id", nullable = false, length = 20)
    private String doctorSystemId;

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

    /** Legacy free-text URL: no longer written by the API and never returned (attachments use documentId). */
    @Column(name = "file_url", length = 500)
    private String fileUrl;

    /** Legacy free-text URL: no longer written by the API and never returned. */
    @Column(name = "thumbnail_url", length = 500)
    private String thumbnailUrl;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "image_date", nullable = false)
    private LocalDate imageDate;

    @Column(name = "reviewed_by_system_id", length = 20)
    private String reviewedBySystemId;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_notes", length = 2000)
    private String reviewNotes;

    /** Attached document (documents.document_id) of the same patient; null when none. */
    @Column(name = "document_id")
    private Integer documentId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (imageDate == null) {
            imageDate = LocalDate.now();
        }
    }
}
