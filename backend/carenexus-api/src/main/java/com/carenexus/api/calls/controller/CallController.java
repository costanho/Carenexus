package com.carenexus.api.calls.controller;

import com.carenexus.api.calls.dto.CallStatusResponse;
import com.carenexus.api.calls.dto.CallTokenResponse;
import com.carenexus.api.calls.dto.ConfirmTimeRequest;
import com.carenexus.api.calls.dto.JoinRequestBody;
import com.carenexus.api.calls.service.CallService;
import com.carenexus.api.common.dto.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Managed video call of one appointment. Access rules live in CallService; every response is
 * {@code Cache-Control: no-store}.
 */
@RestController
@RequestMapping("/api/appointments/{appointmentId}/call")
@RequiredArgsConstructor
@Tag(name = "Video calls", description = "The managed video call of a VIDEO appointment: status, waiting room, "
        + "admission and short-lived join tokens")
public class CallController {

    private final CallService callService;

    @GetMapping
    @Operation(summary = "Get the call status of an appointment",
            description = "The appointment's doctor and patient, a caregiver with an approved, unexpired JOIN_CALLS grant, "
                    + "and administrators (read only).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The call status as the caller sees it"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "You are not allowed to access this call",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Appointment not found, or it has no video call",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<CallStatusResponse> getStatus(@PathVariable Integer appointmentId) {
        return noStore(callService.getStatus(appointmentId));
    }

    @PostMapping("/start")
    @Operation(summary = "Start or rejoin the call",
            description = "The appointment's doctor, inside the join window (15 minutes before the start until the end "
                    + "plus 60 minutes). A READY call becomes LIVE; a LIVE call lets the doctor rejoin.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The call status"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Only the appointment's doctor can manage this call",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Appointment not found, or it has no video call",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Time not confirmed, room being prepared, outside the join "
                    + "window, cancelled or ended",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<CallStatusResponse> start(@PathVariable Integer appointmentId) {
        return noStore(callService.start(appointmentId));
    }

    @PostMapping("/join-request")
    @Operation(summary = "Ask to join the call (waiting room)",
            description = "The appointment's patient, or a caregiver with JOIN_CALLS (re-checked on every call). Body "
                    + "optional; actingForPatient is used for caregivers only. Idempotent while a request is open.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The call status with the caller's participant row"),
            @ApiResponse(responseCode = "400", description = "Doctors join by starting the call, or a malformed body",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "You are not allowed to access this call",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Appointment not found, or it has no video call",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Not started yet, outside the join window, cancelled or ended",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<CallStatusResponse> requestJoin(@PathVariable Integer appointmentId,
                                                          @Valid @RequestBody(required = false) JoinRequestBody body) {
        return noStore(callService.requestJoin(appointmentId, body != null ? body.getActingForPatient() : null));
    }

    @PostMapping("/admissions/{participantId}/admit")
    @Operation(summary = "Admit a waiting participant",
            description = "The appointment's doctor. Consent is re-checked; a caregiver who lost JOIN_CALLS is denied.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The call status"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Only the appointment's doctor can manage this call",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Appointment, video call or participant not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "The request is no longer waiting, or the person lost permission",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<CallStatusResponse> admit(@PathVariable Integer appointmentId,
                                                    @PathVariable Long participantId) {
        return noStore(callService.admit(appointmentId, participantId));
    }

    @PostMapping("/admissions/{participantId}/deny")
    @Operation(summary = "Deny a waiting participant", description = "The appointment's doctor.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The call status"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Only the appointment's doctor can manage this call",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Appointment, video call or participant not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "This join request is no longer waiting",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<CallStatusResponse> deny(@PathVariable Integer appointmentId,
                                                   @PathVariable Long participantId) {
        return noStore(callService.deny(appointmentId, participantId));
    }

    @PostMapping("/admissions/{participantId}/remove")
    @Operation(summary = "Remove an admitted participant from the call", description = "The appointment's doctor.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The call status"),
            @ApiResponse(responseCode = "400", description = "You cannot remove yourself",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Only the appointment's doctor can manage this call",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Appointment, video call or participant not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "This person is not in the call",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<CallStatusResponse> remove(@PathVariable Integer appointmentId,
                                                     @PathVariable Long participantId) {
        return noStore(callService.remove(appointmentId, participantId));
    }

    @PostMapping("/token")
    @Operation(summary = "Get a short-lived token to connect to the live call",
            description = "The appointment's doctor or patient, or a caregiver with a participant row or JOIN_CALLS now; "
                    + "admitted participants only. Consent is re-checked. The token is never logged.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Server URL, token and expiry"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "You are not allowed to access this call",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Appointment not found, or it has no video call",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Not live, not admitted, access removed, outside the join "
                    + "window, or video calls temporarily unavailable",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<CallTokenResponse> token(@PathVariable Integer appointmentId) {
        return noStore(callService.issueToken(appointmentId));
    }

    @PostMapping("/leave")
    @Operation(summary = "Leave the call or withdraw the join request",
            description = "Anyone with an open participant row; without one the status rule decides (no-op).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The call status"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "You are not allowed to access this call",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Appointment not found, or it has no video call",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<CallStatusResponse> leave(@PathVariable Integer appointmentId) {
        return noStore(callService.leave(appointmentId));
    }

    @PostMapping("/end")
    @Operation(summary = "End the call for everyone", description = "The appointment's doctor; no-op when already ended.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The call status"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Only the appointment's doctor can manage this call",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Appointment not found, or it has no video call",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "The call has not started",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<CallStatusResponse> end(@PathVariable Integer appointmentId) {
        return noStore(callService.end(appointmentId));
    }

    @PostMapping("/confirm-time")
    @Operation(summary = "Confirm the appointment time of a call that needs it",
            description = "The appointment's doctor. scheduledAt is the clinic wall-clock time the doctor saw and must "
                    + "equal the stored appointment time to the minute. The body is required (validated first: an "
                    + "invalid body answers 400 for every caller).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The call status (now PREPARING)"),
            @ApiResponse(responseCode = "400", description = "Malformed request body, or scheduledAt: is required",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Only the appointment's doctor can manage this call",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Appointment not found, or it has no video call",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Already confirmed, or the appointment time has changed",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<CallStatusResponse> confirmTime(@PathVariable Integer appointmentId,
                                                          @Valid @RequestBody ConfirmTimeRequest body) {
        return noStore(callService.confirmTime(appointmentId, body.getScheduledAt()));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
