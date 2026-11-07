package com.carenexus.api.calls.controller;

import com.carenexus.api.calls.dto.CallStatusResponse;
import com.carenexus.api.calls.dto.CallTokenResponse;
import com.carenexus.api.calls.dto.ConfirmTimeRequest;
import com.carenexus.api.calls.dto.JoinRequestBody;
import com.carenexus.api.calls.service.CallService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Managed video call of one appointment. Access rules live in CallService.
 */
@RestController
@RequestMapping("/api/appointments/{appointmentId}/call")
@RequiredArgsConstructor
public class CallController {

    private final CallService callService;

    @GetMapping
    public ResponseEntity<CallStatusResponse> getStatus(@PathVariable Integer appointmentId) {
        return noStore(callService.getStatus(appointmentId));
    }

    @PostMapping("/start")
    public ResponseEntity<CallStatusResponse> start(@PathVariable Integer appointmentId) {
        return noStore(callService.start(appointmentId));
    }

    @PostMapping("/join-request")
    public ResponseEntity<CallStatusResponse> requestJoin(@PathVariable Integer appointmentId,
                                                          @RequestBody(required = false) JoinRequestBody body) {
        return noStore(callService.requestJoin(appointmentId, body != null ? body.getActingForPatient() : null));
    }

    @PostMapping("/admissions/{participantId}/admit")
    public ResponseEntity<CallStatusResponse> admit(@PathVariable Integer appointmentId,
                                                    @PathVariable Long participantId) {
        return noStore(callService.admit(appointmentId, participantId));
    }

    @PostMapping("/admissions/{participantId}/deny")
    public ResponseEntity<CallStatusResponse> deny(@PathVariable Integer appointmentId,
                                                   @PathVariable Long participantId) {
        return noStore(callService.deny(appointmentId, participantId));
    }

    @PostMapping("/admissions/{participantId}/remove")
    public ResponseEntity<CallStatusResponse> remove(@PathVariable Integer appointmentId,
                                                     @PathVariable Long participantId) {
        return noStore(callService.remove(appointmentId, participantId));
    }

    @PostMapping("/token")
    public ResponseEntity<CallTokenResponse> token(@PathVariable Integer appointmentId) {
        return noStore(callService.issueToken(appointmentId));
    }

    @PostMapping("/leave")
    public ResponseEntity<CallStatusResponse> leave(@PathVariable Integer appointmentId) {
        return noStore(callService.leave(appointmentId));
    }

    @PostMapping("/end")
    public ResponseEntity<CallStatusResponse> end(@PathVariable Integer appointmentId) {
        return noStore(callService.end(appointmentId));
    }

    @PostMapping("/confirm-time")
    public ResponseEntity<CallStatusResponse> confirmTime(@PathVariable Integer appointmentId,
                                                          @RequestBody(required = false) ConfirmTimeRequest body) {
        return noStore(callService.confirmTime(appointmentId, body != null ? body.getScheduledAt() : null));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
