package com.carenexus.api.account.mfa.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Body of {@code POST /api/auth/mfa/setup/start}. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "MfaChallengeRequest", description = "Challenge token of the sign-in (type MFA_SETUP)")
public class MfaChallengeRequest {

    @NotBlank(message = MfaFormats.REQUIRED_MESSAGE)
    @Size(max = MfaFormats.CHALLENGE_TOKEN_MAX, message = "must be at most 128 characters")
    @Schema(description = "challenge.token of the sign-in response (type MFA_SETUP)",
            maxLength = MfaFormats.CHALLENGE_TOKEN_MAX)
    private String challengeToken;

    /** The token is a credential: keep it out of logs. */
    @Override
    public String toString() {
        return "MfaChallengeRequest";
    }
}
