import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { ErrorBoundary } from 'react-error-boundary';
import Layout from './components/Layout.jsx';
import RegisterPage from './pages/RegisterPage.jsx';
import DashboardPage from './pages/DashboardPage.jsx';
import TransferPage from './pages/TransferPage.jsx';
import HistoryPage from './pages/HistoryPage.jsx';

function AppErrorFallback({ error, resetErrorBoundary }) {
  return (
    <div style={{ padding: 32, maxWidth: 500, margin: '0 auto' }}>
      <h2 style={{ color: 'var(--color-error, #c0392b)' }}>Something went wrong</h2>
      <pre style={{ background: '#f4f4f4', padding: 12, borderRadius: 4, fontSize: 13, overflowX: 'auto' }}>
        {error.message}
      </pre>
      <button
        onClick={resetErrorBoundary}
        style={{ marginTop: 16, padding: '8px 20px', cursor: 'pointer' }}
      >
        Try again
      </button>
    </div>
  );
}

export default function App() {
  return (
    <ErrorBoundary FallbackComponent={AppErrorFallback}>
      <BrowserRouter>
        <Routes>
          <Route path="/" element={<Layout />}>
            <Route index element={<Navigate to="/dashboard" replace />} />
            <Route path="register" element={<RegisterPage />} />
            <Route path="dashboard" element={<DashboardPage />} />
            <Route path="transfer" element={<TransferPage />} />
            <Route path="history" element={<HistoryPage />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </ErrorBoundary>
  );
}
