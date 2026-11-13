import { useCallback, useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { getUser } from '@/lib/auth/auth.storage';

export interface CurrentUser {
  user_id:    number;
  system_id:  string;
  first_name: string;
  last_name:  string;
  email:      string;
  phone:      string | null;
  role:       string;
  is_active:  number;
  avatar_url: string | null;
}

interface ApiUser {
  user_id:   number;
  system_id: string;
  name:      string;
  email:     string;
  role:      string;
}

interface State {
  user:    CurrentUser | null;
  loading: boolean;
  error:   string | null;
}

export interface CurrentUserState extends State {
  /**
   * Loads the signed-in user again (stored session first, then GET /api/users/me). Hooks that wait on the user
   * call this from their own retry, so a failed first load can recover without restarting the app.
   */
  refetch: () => void;
}

function toCurrentUser(data: ApiUser): CurrentUser {
  const parts      = (data.name ?? '').trim().split(/\s+/);
  const first_name = parts[0] ?? '';
  const last_name  = parts.slice(1).join(' ');
  return {
    user_id:    data.user_id,
    system_id:  data.system_id,
    first_name,
    last_name,
    email:      data.email,
    phone:      null,
    role:       data.role,
    is_active:  1,
    avatar_url: null,
  };
}

export function useCurrentUser(): CurrentUserState {
  const [state, setState] = useState<State>({ user: null, loading: true, error: null });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    // The user saved at login/refresh has the same shape as GET /api/users/me,
    // so only hit the network when nothing is stored.
    getUser()
      .then(stored => {
        if (stored?.system_id) return stored as ApiUser;
        return api.get<ApiUser>('/api/users/me').then(({ data }) => data);
      })
      .then(data => { if (!cancelled) setState({ user: toCurrentUser(data), loading: false, error: null }); })
      .catch(() => { if (!cancelled) setState({ user: null, loading: false, error: 'Failed to load user.' }); });
    return () => { cancelled = true; };
  }, [attempt]);

  const refetch = useCallback(() => {
    // Keep a user that is already loaded on screen; only a failed load goes back to "loading".
    setState(prev => (prev.user ? prev : { user: null, loading: true, error: null }));
    setAttempt(n => n + 1);
  }, []);

  return { ...state, refetch };
}
