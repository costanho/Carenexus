package com.carenexus.api.account.sessions.controller;

import com.carenexus.api.account.sessions.dto.request.RevokeOtherSessionsRequest;
import com.carenexus.api.account.sessions.dto.response.RevokeOtherSessionsResponse;
import com.carenexus.api.account.sessions.dto.response.SessionResponse;
import com.carenexus.api.account.sessions.service.SessionService;
import com.carenexus.api.common.dto.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The caller's own sign-in sessions.
 *
 * - GET    /api/users/me/sessions                  active sessions (array, cap 50), current first
 * - DELETE /api/users/me/sessions/{sessionId}      end one session (204; 404 when it is not an active own session)
 * - POST   /api/users/me/sessions/revoke-others    end every other session, keeping {keepSessionId}
 *
 * Any signed-in user, for their own sessions only. Ending a session revokes its refresh token; an access token that
 * device already holds keeps working until it expires.
 */
@RestController
@RequestMapping("/api/users/me/sessions")
@RequiredArgsConstructor
@Tag(name = "Sessions", description = "Active sign-in sessions of the caller")
public class SessionController {

    private final SessionService sessionService;

    @GetMapping
    @Operation(summary = "List my active sign-in sessions",
            description = "Any signed-in user, own sessions only. One entry per session id with a refresh token "
                    + "that is neither revoked nor expired. The entry matching the 'current' parameter comes first "
                    + "and has current = true, then the rest by last use, newest first. Hard cap 50, not paged. "
                    + "No side effects; not audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The caller's active sessions (possibly empty)",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = SessionResponse.class)))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<List<SessionResponse>> listSessions(
            @Parameter(description = "The caller's own session id (tokens.sessionId). Marks that entry as current; a "
                    + "value that matches nothing marks nothing and is not an error",
                    example = "7c1f0c5e-3f0a-4b7e-9a51-2d0d6f6f1c3a")
            @RequestParam(name = "current", required = false) String current) {
        return ResponseEntity.ok(sessionService.listMySessions(current));
    }

    @DeleteMapping("/{sessionId}")
    @Operation(summary = "End one of my sign-in sessions",
            description = "Any signed-in user, own sessions only. Revokes the refresh token of that session, so the "
                    + "device is signed out at its next token refresh (its access token works until it expires). "
                    + "Publishes no sign-out event. Writes one SESSION_REVOKE audit row. Anything that is not an "
                    + "active session of the caller, a value that is not a UUID included, answers 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "The session was ended"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Session not found: not a UUID, unknown, another user's, "
                    + "or already ended or expired",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> revokeSession(
            @Parameter(description = "Session id from the session list", example = "2b9d4a70-51c6-4e0e-8f3a-9c1e7d2a6b44")
            @PathVariable String sessionId) {
        sessionService.revokeMySession(sessionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/revoke-others")
    @Operation(summary = "End all my other sign-in sessions",
            description = "Any signed-in user, own sessions only. Ends every active session except keepSessionId, "
                    + "which must be an active session of the caller (otherwise 404 and nothing is ended). The kept "
                    + "session and the caller's tokens are untouched. Publishes no sign-out event. Writes one "
                    + "SESSION_REVOKE audit row when at least one session ended. Repeatable: a second call returns "
                    + "revokedCount 0.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Number of sessions ended"),
            @ApiResponse(responseCode = "400", description = "keepSessionId missing, blank or not a UUID (code "
                    + "VALIDATION_FAILED), or a malformed body",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Session not found: keepSessionId is not an active session "
                    + "of the caller",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<RevokeOtherSessionsResponse> revokeOtherSessions(
            @Valid @RequestBody RevokeOtherSessionsRequest request) {
        return ResponseEntity.ok(sessionService.revokeMyOtherSessions(request.getKeepSessionId()));
    }
}
