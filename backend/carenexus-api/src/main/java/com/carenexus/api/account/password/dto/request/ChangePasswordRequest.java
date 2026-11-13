package com.carenexus.api.account.password.dto.request;

import com.carenexus.api.auth.dto.validation.StrongPassword;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Body of {@code POST /api/users/me/password}. Passwords are used exactly as sent (never trimmed).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "The current password, the new one and the session to keep")
public class ChangePasswordRequest {

    /** A UUID in the 8-4-4-4-12 hexadecimal form of {@code tokens.sessionId}. */
    public static final String SESSION_ID_REGEX =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    @NotBlank(message = "Current password is required")
    @Size(max = 128, message = "Current password must be at most 128 characters")
    @Schema(description = "The password the account has now", format = "password", maxLength = 128)
    private String currentPassword;

    @StrongPassword
    @Schema(description = "At least 8 characters with a letter and a digit, at most 72 bytes in UTF-8; must differ "
            + "from the current password", example = "Harvest2026moon", format = "password")
    private String newPassword;

    @Pattern(regexp = SESSION_ID_REGEX, message = "Session id is invalid")
    @Schema(description = "tokens.sessionId of the caller's session, which stays signed in. When absent every "
            + "session ends, the calling one included. A UUID that is not an active session of the caller keeps "
            + "nothing.", example = "6f1c2c1e-8a54-4f0b-9d0e-2b7a4c9e5d31", nullable = true)
    private String keepSessionId;

    @Override
    public String toString() {
        return "ChangePasswordRequest[credentials hidden, keepSessionId=" + keepSessionId + "]";
    }
}
