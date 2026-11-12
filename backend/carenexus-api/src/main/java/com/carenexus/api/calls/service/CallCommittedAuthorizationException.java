package com.carenexus.api.calls.service;

import com.carenexus.api.common.exception.AuthorizationException;

/** A 403 whose state change (participant removed after a failed consent re-check) must still commit. */
public class CallCommittedAuthorizationException extends AuthorizationException {
    public CallCommittedAuthorizationException(String message, String resourceType, Integer resourceId) {
        super(message, resourceType, resourceId);
    }
}
