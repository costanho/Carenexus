import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';

export interface CurrentUser {
  user_id:    number;
  first_name: string;
  last_name:  string;
  email:      string;
  phone:      string | null;
  role:       string;
  is_active:  number;
  avatar_url: string | null;
}

interface State {
  user:    CurrentUser | null;
  loading: boolean;
  error:   string | null;
}

export function useCurrentUser() {
  const [state, setState] = useState<State>({ user: null, loading: true, error: null });

  useEffect(() => {
    api.get<CurrentUser>('/api/users/me')
      .then(({ data }) => setState({ user: data, loading: false, error: null }))
      .catch(() => setState({ user: null, loading: false, error: 'Failed to load user.' }));
  }, []);

  return state;
}
