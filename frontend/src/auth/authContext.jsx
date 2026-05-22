import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import { clearAuthSession } from '../api/apiUtils.js';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [auth, setAuth] = useState(() => {
    const token = sessionStorage.getItem('accessToken');
    const userId = sessionStorage.getItem('userId');
    const email = sessionStorage.getItem('email');
    return token ? { token, userId, email } : null;
  });

  const login = useCallback((loginResponse) => {
    sessionStorage.setItem('accessToken', loginResponse.accessToken);
    sessionStorage.setItem('userId', loginResponse.userId);
    sessionStorage.setItem('email', loginResponse.email);
    setAuth({ token: loginResponse.accessToken, userId: loginResponse.userId, email: loginResponse.email });
  }, []);

  const logout = useCallback(() => {
    clearAuthSession();
    setAuth(null);
  }, []);

  const value = useMemo(() => ({ auth, login, logout }), [auth, login, logout]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  return useContext(AuthContext);
}
