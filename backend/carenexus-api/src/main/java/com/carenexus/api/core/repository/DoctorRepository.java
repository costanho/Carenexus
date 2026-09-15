package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.Doctor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DoctorRepository extends JpaRepository<Doctor, Integer> {
    Optional<Doctor> findByUserId(Integer userId);
    Optional<Doctor> findByLicenseNo(String licenseNo);
}
