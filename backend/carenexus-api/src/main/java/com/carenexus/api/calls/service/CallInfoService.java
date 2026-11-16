package com.carenexus.api.calls.service;

import com.carenexus.api.calls.event.AppointmentCallRelevantChangeEvent;
import com.carenexus.api.calls.model.CallSession;
import com.carenexus.api.calls.repository.CallSessionRepository;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.repository.AppointmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Call fields for appointment DTOs, in one batch query (no N+1). Read only: never creates sessions.
 * The join route never contains a provider URL, room name or token.
 */
@Service
@RequiredArgsConstructor
public class CallInfoService {

    public static final String CALL_ROUTE_PREFIX = "/dashboard/call/";

    private final CallSessionRepository callSessionRepository;
    private final AppointmentRepository appointmentRepository;
    private final CallSessionService callSessionService;

    /** The four call fields of an appointment response. */
    public record CallInfo(String callStatus, String callJoinRoute, Instant joinWindowOpensAt, Instant joinWindowClosesAt) {
        public static final CallInfo NONE = new CallInfo(null, null, null, null);
    }

    public static String joinRoute(Integer appointmentId) {
        return CALL_ROUTE_PREFIX + appointmentId;
    }

    /** By appointment id (loads the appointments in one query, then the sessions in one query). */
    public Map<Integer, CallInfo> describe(Collection<Integer> appointmentIds) {
        if (appointmentIds == null || appointmentIds.isEmpty()) {
            return Map.of();
        }
        List<Integer> ids = appointmentIds.stream().filter(Objects::nonNull).distinct().toList();
        return describeAppointments(appointmentRepository.findAllById(ids));
    }

    /** For appointments already loaded by the caller. Missing keys mean "no call" ({@link CallInfo#NONE}). */
    public Map<Integer, CallInfo> describeAppointments(Collection<Appointment> appointments) {
        if (appointments == null || appointments.isEmpty()) {
            return Map.of();
        }
        List<Integer> videoIds = appointments.stream()
                .filter(a -> CallSessionService.VIDEO.equals(a.getType()))
                .map(Appointment::getAppointmentId)
                .toList();
        if (videoIds.isEmpty()) {
            return Map.of();
        }
        Map<Integer, CallSession> sessions = callSessionRepository.findByAppointmentIdIn(videoIds).stream()
                .collect(Collectors.toMap(CallSession::getAppointmentId, Function.identity(), (a, b) -> a));
        Map<Integer, CallInfo> result = new HashMap<>();
        for (Appointment appointment : appointments) {
            if (!CallSessionService.VIDEO.equals(appointment.getType())) {
                continue;
            }
            CallSession session = sessions.get(appointment.getAppointmentId());
            if (session != null) {
                result.put(appointment.getAppointmentId(), fromSession(session));
            } else if (CallSessionService.ACTIVE_APPOINTMENT_STATUSES.contains(appointment.getStatus())) {
                // Created lazily as NEEDS_TIME_CONFIRMATION on the first call status read.
                result.put(appointment.getAppointmentId(), new CallInfo(CallSession.NEEDS_TIME_CONFIRMATION,
                        joinRoute(appointment.getAppointmentId()), null, null));
            }
        }
        return result;
    }

    public CallInfo describeOne(Appointment appointment) {
        return describeAppointments(List.of(appointment)).getOrDefault(appointment.getAppointmentId(), CallInfo.NONE);
    }

    /**
     * Call fields as they will be once the appointment change commits (the session is synced BEFORE_COMMIT,
     * after the response has been built).
     */
    public CallInfo describeAfterChange(Appointment appointment, AppointmentCallRelevantChangeEvent.Kind kind) {
        if (kind == null) {
            return describeOne(appointment);
        }
        if (kind == AppointmentCallRelevantChangeEvent.Kind.ENDED) {
            // Outcome: a session that has not gone LIVE (and is not already final) becomes ENDED on commit.
            CallInfo current = describeOne(appointment);
            if (current.callStatus() == null || CallSession.LIVE.equals(current.callStatus())
                    || CallSession.ENDED.equals(current.callStatus())
                    || CallSession.CANCELLED.equals(current.callStatus())) {
                return current;
            }
            return new CallInfo(CallSession.ENDED, current.callJoinRoute(),
                    current.joinWindowOpensAt(), current.joinWindowClosesAt());
        }
        if (kind == AppointmentCallRelevantChangeEvent.Kind.DURATION_CHANGED) {
            CallInfo current = describeOne(appointment);
            if (current.joinWindowClosesAt() == null || CallSession.ENDED.equals(current.callStatus())
                    || CallSession.CANCELLED.equals(current.callStatus())) {
                return current;
            }
            CallSession preview = new CallSession();
            callSessionService.applySchedule(preview, appointment);
            return new CallInfo(current.callStatus(), current.callJoinRoute(),
                    preview.getJoinOpensAt(), preview.getJoinClosesAt());
        }
        if (!CallSessionService.VIDEO.equals(appointment.getType())) {
            return CallInfo.NONE;
        }
        if (kind == AppointmentCallRelevantChangeEvent.Kind.CANCELLED || "CANCELLED".equals(appointment.getStatus())) {
            CallSession session = callSessionRepository.findByAppointmentId(appointment.getAppointmentId()).orElse(null);
            if (session == null) {
                return CallInfo.NONE;
            }
            String status = CallSession.ENDED.equals(session.getStatus()) ? CallSession.ENDED : CallSession.CANCELLED;
            return new CallInfo(status, joinRoute(appointment.getAppointmentId()),
                    session.getJoinOpensAt(), session.getJoinClosesAt());
        }
        // CREATED_VIDEO, RESCHEDULED, TYPE_CHANGED to VIDEO: a fresh PREPARING session at the current time.
        CallSession preview = new CallSession();
        callSessionService.applySchedule(preview, appointment);
        return new CallInfo(CallSession.PREPARING, joinRoute(appointment.getAppointmentId()),
                preview.getJoinOpensAt(), preview.getJoinClosesAt());
    }

    private static CallInfo fromSession(CallSession session) {
        boolean hasWindow = !CallSession.NEEDS_TIME_CONFIRMATION.equals(session.getStatus());
        return new CallInfo(session.getStatus(), joinRoute(session.getAppointmentId()),
                hasWindow ? session.getJoinOpensAt() : null,
                hasWindow ? session.getJoinClosesAt() : null);
    }
}
