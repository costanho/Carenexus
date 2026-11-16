package com.carenexus.api.communications.dto.request;

/**
 * Bean Validation texts and patterns shared by the messaging request DTOs (API contract
 * be-communications-hardening 0.4). The error envelope reports them as {@code "<field>: <text>"}.
 */
public final class MessagingValidation {

    /** Client retry keys ({@code clientThreadId}, {@code clientMessageId}). */
    public static final String CLIENT_ID = "^[A-Za-z0-9_-]{8,64}$";

    public static final String REQUIRED = "is required";
    public static final String BODY_EMPTY = "must not be empty";
    public static final String BODY_TOO_LONG = "must be at most 4000 characters";
    public static final String SUBJECT_TOO_LONG = "must be at most 200 characters";
    public static final String CLIENT_ID_FORMAT = "must be 8-64 letters, digits, '-' or '_'";
    public static final String SYSTEM_ID_FORMAT = "must be a valid system id";
    public static final String TOO_MANY_ENTRIES = "must contain at most 50 entries";
    public static final String NO_ENTRIES = "choose at least one person to add";
    public static final String TRUE_OR_FALSE = "must be true or false";
    public static final String NOT_NEGATIVE = "must be 0 or greater";

    /** Longest message body (characters, as the client counts them). */
    public static final int MAX_BODY_LENGTH = 4000;
    /** Longest conversation subject. */
    public static final int MAX_SUBJECT_LENGTH = 200;
    /** Most people named in one request. */
    public static final int MAX_MEMBERS_PER_REQUEST = 50;

    private MessagingValidation() {
    }
}
