package com.carenexus.api.caregiveraccess.service;

import com.carenexus.api.caregiveraccess.repository.AccessPeopleRepository;
import com.carenexus.api.caregiveraccess.repository.AccessPeopleRepository.Person;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Batched people lookups and the "First Last" names used in responses and notification texts. */
@Component
@RequiredArgsConstructor
public class AccessPeople {

    static final String SOME_PATIENT = "A patient";
    static final String SOME_CAREGIVER = "A caregiver";

    private final AccessPeopleRepository repository;

    /** One query for every id given (nulls ignored); ids without an account are absent from the map. */
    public Map<String, Person> byId(Collection<String> systemIds) {
        Set<String> ids = new LinkedHashSet<>();
        if (systemIds != null) {
            systemIds.stream().filter(Objects::nonNull).forEach(ids::add);
        }
        Map<String, Person> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        repository.findPeople(ids).forEach(person -> result.putIfAbsent(person.getSystemId(), person));
        return result;
    }

    /** "First Last" of the account, or null when there is none. */
    public static String fullName(Person person) {
        if (person == null) {
            return null;
        }
        String first = person.getFirstName() == null ? "" : person.getFirstName().trim();
        String last = person.getLastName() == null ? "" : person.getLastName().trim();
        String name = (first + " " + last).trim();
        return name.isEmpty() ? null : name;
    }

    /** Name of a patient for a notification text: "First Last", or "A patient". */
    public String patientName(String patientSystemId) {
        return nameOr(patientSystemId, SOME_PATIENT);
    }

    /** Name of a caregiver for a notification text: "First Last", or "A caregiver". */
    public String caregiverName(String caregiverSystemId) {
        return nameOr(caregiverSystemId, SOME_CAREGIVER);
    }

    private String nameOr(String systemId, String fallback) {
        if (systemId == null) {
            return fallback;
        }
        String name = fullName(byId(Set.of(systemId)).get(systemId));
        return name == null ? fallback : name;
    }
}
