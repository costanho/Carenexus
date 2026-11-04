package com.carenexus.api.calls.service;

import com.carenexus.api.common.exception.ConflictException;

/**
 * A 409 whose state change (e.g. "room is being prepared", join request DENIED after a failed consent check)
 * must still commit. CallService does not roll back for it.
 */
public class CallCommittedConflictException extends ConflictException {
    public CallCommittedConflictException(String message) {
        super(message);
    }
}
