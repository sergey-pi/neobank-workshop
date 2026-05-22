import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from './authContext.jsx';

export default function RequireAuth({ children }) {
  const { auth } = useAuth();
  const location = useLocation();

  if (!auth) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  return children;
}
