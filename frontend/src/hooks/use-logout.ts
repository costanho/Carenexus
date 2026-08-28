import { useRouter } from 'expo-router';
import { useState } from 'react';
import { Platform } from 'react-native';
import { logout } from '@/lib/auth/auth.service';

export function useLogout() {
  const router = useRouter();
  const [loading, setLoading] = useState(false);

  async function handleLogout() {
    if (loading) return;
    setLoading(true);
    try {
      await logout();
      const path = Platform.OS === 'web'
        ? '/auth/login/web/login-web'
        : '/auth/login/mobile/login-mobile';
      router.replace(path as any);
    } finally {
      setLoading(false);
    }
  }

  return { handleLogout, loading };
}
