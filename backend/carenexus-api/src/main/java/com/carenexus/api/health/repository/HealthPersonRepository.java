package com.carenexus.api.health.repository;

import com.carenexus.api.auth.model.User;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Read-only name lookup for the people shown next to readings and symptom logs (recorder, author, patient).
 * One query per response, never per row.
 */
public interface HealthPersonRepository extends Repository<User, Integer> {

    @Query("SELECT u.systemId AS systemId, u.firstName AS firstName, u.lastName AS lastName "
            + "FROM User u WHERE u.systemId IN :systemIds")
    List<PersonName> findNames(@Param("systemIds") Collection<String> systemIds);

    interface PersonName {

        String getSystemId();

        String getFirstName();

        String getLastName();
    }
}
