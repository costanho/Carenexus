import { api } from '@/lib/auth/auth.interceptor';
import type { CallStatusResponse, CallTokenResponse } from './types';

// One function per endpoint of /api/appointments/{appointmentId}/call (CONTRACT.md 8.3).
// Never log responses: the token response carries a room credential.

const base = (appointmentId: number) => `/api/appointments/${appointmentId}/call`;

export async function getCallStatus(appointmentId: number): Promise<CallStatusResponse> {
  const { data } = await api.get<CallStatusResponse>(base(appointmentId));
  return data;
}

export async function startCall(appointmentId: number): Promise<CallStatusResponse> {
  const { data } = await api.post<CallStatusResponse>(`${base(appointmentId)}/start`);
  return data;
}

export async function requestToJoin(appointmentId: number, actingForPatient: boolean): Promise<CallStatusResponse> {
  const { data } = await api.post<CallStatusResponse>(`${base(appointmentId)}/join-request`, { actingForPatient });
  return data;
}

export async function admitParticipant(appointmentId: number, participantId: number): Promise<CallStatusResponse> {
  const { data } = await api.post<CallStatusResponse>(`${base(appointmentId)}/admissions/${participantId}/admit`);
  return data;
}

export async function denyParticipant(appointmentId: number, participantId: number): Promise<CallStatusResponse> {
  const { data } = await api.post<CallStatusResponse>(`${base(appointmentId)}/admissions/${participantId}/deny`);
  return data;
}

export async function removeParticipant(appointmentId: number, participantId: number): Promise<CallStatusResponse> {
  const { data } = await api.post<CallStatusResponse>(`${base(appointmentId)}/admissions/${participantId}/remove`);
  return data;
}

export async function getCallToken(appointmentId: number): Promise<CallTokenResponse> {
  const { data } = await api.post<CallTokenResponse>(`${base(appointmentId)}/token`);
  return data;
}

export async function leaveCall(appointmentId: number): Promise<CallStatusResponse> {
  const { data } = await api.post<CallStatusResponse>(`${base(appointmentId)}/leave`);
  return data;
}

export async function endCall(appointmentId: number): Promise<CallStatusResponse> {
  const { data } = await api.post<CallStatusResponse>(`${base(appointmentId)}/end`);
  return data;
}

export async function confirmCallTime(appointmentId: number, scheduledAt: string): Promise<CallStatusResponse> {
  const { data } = await api.post<CallStatusResponse>(`${base(appointmentId)}/confirm-time`, { scheduledAt });
  return data;
}
