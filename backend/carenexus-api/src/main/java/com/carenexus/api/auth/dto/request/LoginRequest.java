package com.carenexus.api.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Body of {@code POST /api/auth/login}. The email is trimmed and lower-cased when it is set; the password is used
 * exactly as sent. Unknown properties (the screens also send {@code role}) are ignored.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Sign-in credentials and an optional description of the device")
public class LoginRequest {

    @NotBlank(message = "Email is required")
    @Size(max = 150, message = "must be at most 150 characters")
    @Schema(example = "linda@example.com", maxLength = 150)
    private String email;

    @NotEmpty(message = "Password is required")
    @Size(max = 200, message = "must be at most 200 characters")
    @Schema(example = "Str0ng-Passw0rd", maxLength = 200)
    private String password;

    @Size(max = 50, message = "must be at most 50 characters")
    @Schema(description = "Free text stored with the session. Conventional values: Web, iOS, Android",
            example = "Web", nullable = true)
    private String deviceType;

    @Size(max = 255, message = "must be at most 255 characters")
    @Schema(description = "Free text stored with the session, for example a short browser and system summary",
            example = "Chrome on macOS", nullable = true)
    private String deviceInfo;

    public void setEmail(String email) {
        this.email = RequestText.cleanLower(email);
    }

    /** Not trimmed: a password is used exactly as sent. */
    public void setPassword(String password) {
        this.password = password;
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = RequestText.clean(deviceType);
    }

    public void setDeviceInfo(String deviceInfo) {
        this.deviceInfo = RequestText.clean(deviceInfo);
    }
}
