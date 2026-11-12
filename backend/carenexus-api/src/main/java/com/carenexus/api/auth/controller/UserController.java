package com.carenexus.api.auth.controller;

import com.carenexus.api.auth.dto.response.CurrentUserResponse;
import com.carenexus.api.auth.service.AuthService;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.service.AuthorizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "Current user", description = "The signed-in account")
public class UserController {

    private final AuthService authService;
    private final AuthorizationService authorizationService;

    /**
     * GET /api/users/me
     * The signed-in user and the current session flags. The identity always comes from the access token.
     */
    @GetMapping("/me")
    @Operation(summary = "Get the signed-in user and the session flags",
            description = "Any signed-in user, for themselves only. Returns the same user fields as sign-in plus "
                    + "the current session flags.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The signed-in user"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token, or the "
                    + "account is no longer active",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "The account no longer exists",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<CurrentUserResponse> getCurrentUser() {
        // Throws an authentication error (401 in the error envelope) when nobody is signed in.
        String systemId = authorizationService.currentSystemId();
        return ResponseEntity.ok(authService.getCurrentUser(systemId));
    }
}
