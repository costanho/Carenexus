package com.carenexus.api.billing.repository;

import com.carenexus.api.billing.model.Invoice;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Invoices. Lists are always paged; combine optional filters with {@link InvoiceSpecifications} and
 * {@code findAll(Specification, Pageable)}.
 */
@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, Integer>, JpaSpecificationExecutor<Invoice> {

    /** Row-locked read for every change of status or of the amount paid (serializes payments on one invoice). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Invoice i WHERE i.invoiceId = :invoiceId")
    Optional<Invoice> findByIdForUpdate(@Param("invoiceId") Integer invoiceId);

    Optional<Invoice> findByInvoiceNo(String invoiceNo);

    List<Invoice> findByInvoiceIdIn(Collection<Integer> invoiceIds);

    // ---- by patient ----

    Page<Invoice> findByPatientSystemId(String patientSystemId, Pageable pageable);

    Page<Invoice> findByPatientSystemIdAndStatusIn(String patientSystemId, Collection<String> statuses,
                                                   Pageable pageable);

    // ---- by issuing doctor ----

    Page<Invoice> findByDoctorSystemId(String doctorSystemId, Pageable pageable);

    Page<Invoice> findByDoctorSystemIdAndStatusIn(String doctorSystemId, Collection<String> statuses,
                                                  Pageable pageable);

    // ---- by patient and issuing doctor (a doctor reads only the invoices they issued) ----

    Page<Invoice> findByPatientSystemIdAndDoctorSystemId(String patientSystemId, String doctorSystemId,
                                                         Pageable pageable);

    Page<Invoice> findByPatientSystemIdAndDoctorSystemIdAndStatusIn(String patientSystemId, String doctorSystemId,
                                                                    Collection<String> statuses, Pageable pageable);

    // ---- by status (administrator lists) ----

    Page<Invoice> findByStatusIn(Collection<String> statuses, Pageable pageable);

    // ---- the invoice of a visit: any status except CANCELLED (same rule as the partial unique indexes) ----

    @Query("SELECT i FROM Invoice i WHERE i.appointmentId = :appointmentId AND i.status <> 'CANCELLED'")
    Optional<Invoice> findActiveByAppointmentId(@Param("appointmentId") Integer appointmentId);

    @Query("SELECT i FROM Invoice i WHERE i.consultationId = :consultationId AND i.status <> 'CANCELLED'")
    Optional<Invoice> findActiveByConsultationId(@Param("consultationId") Integer consultationId);

    @Query("SELECT i FROM Invoice i WHERE i.appointmentId IN :appointmentIds AND i.status <> 'CANCELLED'")
    List<Invoice> findActiveByAppointmentIdIn(@Param("appointmentIds") Collection<Integer> appointmentIds);

    @Query("SELECT i FROM Invoice i WHERE i.consultationId IN :consultationIds AND i.status <> 'CANCELLED'")
    List<Invoice> findActiveByConsultationIdIn(@Param("consultationIds") Collection<Integer> consultationIds);

    // ---- overdue sweep ----

    /**
     * Ids of the invoices that can become OVERDUE: SENT or PARTIALLY_PAID with a due date before {@code cutoff}
     * (exclusive), oldest due date first. Pass a page request to bound the batch.
     */
    @Query("SELECT i.invoiceId FROM Invoice i WHERE i.status IN ('SENT', 'PARTIALLY_PAID') "
            + "AND i.dueDate IS NOT NULL AND i.dueDate < :cutoff ORDER BY i.dueDate ASC, i.invoiceId ASC")
    List<Integer> findIdsDueBefore(@Param("cutoff") LocalDate cutoff, Pageable pageable);

    // ---- numbering ----

    /** Next value of {@code invoice_no_seq}: the numeric part of {@code INV-<yyyy>-<6 digits>}. Never reused. */
    @Query(value = "SELECT nextval('invoice_no_seq')", nativeQuery = true)
    long nextInvoiceNumber();
}
