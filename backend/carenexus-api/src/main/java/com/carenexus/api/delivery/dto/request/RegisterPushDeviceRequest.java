package com.carenexus.api.delivery.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Body of {@code POST /api/notifications/devices}. The device belongs to the signed-in caller; no field names a user.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A push token of one of the caller's devices")
public class RegisterPushDeviceRequest {

    public static final String PLATFORM_PATTERN = "^\\s*(IOS|ANDROID|WEB)\\s*$";
    public static final String PLATFORM_MESSAGE = "must be IOS, ANDROID or WEB";
    public static final String TOKEN_PATTERN = "^[A-Za-z0-9_:\\-\\[\\]]{8,512}$";
    public static final String TOKEN_MESSAGE = "must be 8 to 512 letters, digits or _ : - [ ]";

    @NotNull
    @Pattern(regexp = PLATFORM_PATTERN, flags = Pattern.Flag.CASE_INSENSITIVE, message = PLATFORM_MESSAGE)
    @Schema(description = "Device platform; compared case-insensitively, stored upper-case",
            allowableValues = {"IOS", "ANDROID", "WEB"}, example = "ANDROID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String platform;

    @NotNull
    @Pattern(regexp = TOKEN_PATTERN, message = TOKEN_MESSAGE)
    @Schema(description = "Push token as issued to the device (Expo, FCM or APNs): 8 to 512 letters, digits or _ : - [ ]",
            example = "ExponentPushToken[xxxxxxxxxxxxxxxxxxxxxx]", minLength = 8, maxLength = 512,
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String token;

    /** Never prints the token. */
    @Override
    public String toString() {
        return "RegisterPushDeviceRequest[platform=" + platform + "]";
    }
}
