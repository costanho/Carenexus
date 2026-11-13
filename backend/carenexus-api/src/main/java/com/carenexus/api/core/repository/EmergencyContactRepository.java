package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.EmergencyContact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmergencyContactRepository extends JpaRepository<EmergencyContact, Integer> {
    List<EmergencyContact> findByPatientSystemId(String patientSystemId);
    Optional<EmergencyContact> findByPatientSystemIdAndIsPrimaryTrue(String patientSystemId);
    List<EmergencyContact> findByPatientSystemIdAndIsCaregiver(String patientSystemId, Boolean isCaregiver);
    List<EmergencyContact> findByPatientSystemIdAndIsPrimary(String patientSystemId, Boolean isPrimary);
    Optional<EmergencyContact> findByContactIdAndPatientSystemId(Integer contactId, String patientSystemId);

    /**
     * A patient's contacts in display order: the primary contact first, then oldest first. Capped at 50 rows
     * (at most {@link EmergencyContact#MAX_PER_PATIENT} can be created through the API).
     */
    List<EmergencyContact> findTop50ByPatientSystemIdOrderByIsPrimaryDescContactIdAsc(String patientSystemId);

    long countByPatientSystemId(String patientSystemId);

    /**
     * Clears the primary flag of every contact of the patient. Used inside the transaction that creates a new
     * primary contact.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE EmergencyContact c SET c.isPrimary = false "
            + "WHERE c.patientSystemId = :patientSystemId AND c.isPrimary = true")
    int demotePrimary(@Param("patientSystemId") String patientSystemId);

    /**
     * Clears the primary flag of every contact of the patient except {@code keepContactId}. Used inside the
     * transaction that makes an existing contact the primary one.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE EmergencyContact c SET c.isPrimary = false "
            + "WHERE c.patientSystemId = :patientSystemId AND c.isPrimary = true AND c.contactId <> :keepContactId")
    int demoteOtherPrimary(@Param("patientSystemId") String patientSystemId,
                           @Param("keepContactId") Integer keepContactId);

    /**
     * Locks the patient's row (SELECT ... FOR UPDATE) for the rest of the transaction, so concurrent writes to the
     * same patient's contacts are serialised (the 10-contact cap and the single primary contact hold under
     * concurrency). Empty when the system id is not a patient.
     */
    @Query(value = "SELECT p.system_id FROM patients p WHERE p.system_id = :patientSystemId FOR UPDATE",
            nativeQuery = true)
    Optional<String> lockPatient(@Param("patientSystemId") String patientSystemId);
}
