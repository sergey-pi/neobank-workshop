import { Outlet, NavLink } from 'react-router-dom';

export default function Layout() {
  return (
    <div className="layout">
      <nav className="nav">
        <a href="/" className="nav-brand">NeoBank</a>
        <NavLink to="/dashboard" className={({ isActive }) => 'nav-link' + (isActive ? ' active' : '')}>Dashboard</NavLink>
        <NavLink to="/transfer" className={({ isActive }) => 'nav-link' + (isActive ? ' active' : '')}>Transfer</NavLink>
        <NavLink to="/history" className={({ isActive }) => 'nav-link' + (isActive ? ' active' : '')}>History</NavLink>
        <NavLink to="/register" className={({ isActive }) => 'nav-link' + (isActive ? ' active' : '')}>Register</NavLink>
      </nav>
      <main className="main">
        <Outlet />
      </main>
    </div>
  );
}
