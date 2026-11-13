package com.carenexus.api.admin.settings;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Value type of a system setting. Every value is stored and sent as text; the type says how that text is
 * validated and which editor the admin screen shows.
 */
@Schema(description = "Value type of a system setting; the value itself always travels as a JSON string")
public enum SettingType {
    /** Free text. */
    STRING,
    /** Whole number inside the range of the key. */
    INT,
    /** Decimal number inside the range of the key, at most four decimal places. */
    DECIMAL,
    /** Email address. */
    EMAIL,
    /** Phone number ({@code ValidationPatterns.PHONE}). */
    PHONE,
    /** ISO 4217 currency code, three upper-case letters. */
    CURRENCY
}
