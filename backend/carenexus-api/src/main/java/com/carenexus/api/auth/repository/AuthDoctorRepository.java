package com.carenexus.api.auth.repository;

import com.carenexus.api.core.model.Doctor;
import org.springframework.data.repository.Repository;

/**
 * The one doctor query registration needs beyond {@code DoctorRepository}: licence numbers are stored as typed but
 * must be unique whatever their letter case.
 */
public interface AuthDoctorRepository extends Repository<Doctor, Integer> {

    boolean existsByLicenseNoIgnoreCase(String licenseNo);
}
