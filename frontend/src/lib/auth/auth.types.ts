export type Role = 'patient' | 'doctor' | 'proxy';

export interface LoginPayload {
  email:    string;
  password: string;
  role:     Role;
}

export interface RegisterPayload {
  firstName: string;
  lastName:  string;
  email:     string;
  phone:     string;
  password:  string;
  role:      Role;
}

export interface AuthTokens {
  accessToken:  string;
  refreshToken: string;
  expiresIn:    number; // seconds
}

export interface AuthUser {
  user_id: number;
  name:    string;
  email:   string;
  role:    Role;
}

export interface AuthResponse {
  tokens: AuthTokens;
  user:   AuthUser;
}

/** Route each role maps to after login */
export const ROLE_HOME: Record<Role, string> = {
  patient: '/dashboard/patient',
  doctor:  '/dashboard/doctor',
  proxy:   '/dashboard/proxy',
};
