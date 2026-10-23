import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';

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

function toCurrentUser(data: ApiUser): CurrentUser {
  const parts      = data.name.trim().split(/\s+/);
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

export function useCurrentUser() {
  const [state, setState] = useState<State>({ user: null, loading: true, error: null });

  useEffect(() => {
    api.get<ApiUser>('/api/users/me')
      .then(({ data }) => setState({ user: toCurrentUser(data), loading: false, error: null }))
      .catch(() => setState({ user: null, loading: false, error: 'Failed to load user.' }));
  }, []);

  return state;
}
