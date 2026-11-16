package com.carenexus.api.documents.repository;

import com.carenexus.api.auth.model.User;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;

/**
 * Read-only access to the accounts that uploaded documents, to resolve the uploader's name and role for a whole
 * page in one query.
 */
public interface DocumentUploaderRepository extends Repository<User, Integer> {

    List<User> findBySystemIdIn(Collection<String> systemIds);
}
