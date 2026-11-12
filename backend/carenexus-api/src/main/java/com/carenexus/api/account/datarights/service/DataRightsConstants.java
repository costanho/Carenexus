package com.carenexus.api.account.datarights.service;

/** Names shared by the data-rights services: audit actions, resource types, buckets, templates and texts. */
public final class DataRightsConstants {

    // Audit resource types (CONTRACTS.v2 3.6)
    public static final String RESOURCE_DATA_EXPORT = "DATA_EXPORT";
    public static final String RESOURCE_ACCOUNT = "ACCOUNT";

    // Audit actions
    public static final String ACTION_EXPORT_REQUEST = "DATA_EXPORT_REQUEST";
    public static final String ACTION_EXPORT_DOWNLOAD = "DATA_EXPORT_DOWNLOAD";
    public static final String ACTION_CLOSE = "ACCOUNT_CLOSE";
    public static final String ACTION_DELETION_REQUEST = "ACCOUNT_DELETION_REQUEST";
    public static final String ACTION_DELETION_CANCEL = "ACCOUNT_DELETION_CANCEL";
    public static final String ACTION_DELETION_APPROVE = "ACCOUNT_DELETION_APPROVE";
    public static final String ACTION_DELETION_REJECT = "ACCOUNT_DELETION_REJECT";

    // Rate-limit buckets (CONTRACTS.v2 3.5)
    public static final String BUCKET_PASSWORD = "password-change";
    public static final String BUCKET_EXPORT = "data-export";

    // Email templates (classpath messages/<id>.txt)
    public static final String TEMPLATE_EXPORT_READY = "account-export-ready";
    public static final String TEMPLATE_CLOSED = "account-closed";
    public static final String TEMPLATE_CLOSED_DELETION = "account-closed-deletion";

    // Error codes and texts
    public static final String CODE_PASSWORD_INCORRECT = "PASSWORD_INCORRECT";
    public static final String MSG_PASSWORD_INCORRECT = "Password is incorrect";

    // Anonymised account (be-admin-users keys its 'anonymised' flag on exactly this shape)
    public static final String ANONYMISED_FIRST_NAME = "Deleted";
    public static final String ANONYMISED_LAST_NAME = "User";
    public static final String ANONYMISED_CLOSURE_REASON = "Account deleted on request";

    // Export storage
    public static final String EXPORT_STORAGE_AREA = "exports";
    public static final String EXPORT_CONTENT_TYPE = "application/json";

    /** Hard cap of the owner's lists (exports, deletion requests). */
    public static final int OWN_LIST_CAP = 20;

    private DataRightsConstants() {
    }
}
