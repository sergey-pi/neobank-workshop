---
applyTo: "frontend/**/*"
---

# Frontend Instructions

React 18 + Vite 8 single-page app, plain JavaScript/JSX (no TypeScript), React Router 6. Lives outside the Maven build; CI does not build it.

## Commands

```bash
cd frontend
npm ci
npm run dev      # http://localhost:3000, Vite proxies /api/v1/* to the services
npm run build    # verification step — there is no test or lint script
npm run preview
```

## Layout

```
src/
├── api/         # apiUtils.js + one thin fetch wrapper per service (userApi, ledgerApi, paymentApi)
├── auth/        # authContext.jsx (AuthProvider/useAuth), RequireAuth.jsx (route guard)
├── components/  # Layout, KycBadge
└── pages/       # Login, Register, Dashboard, Transfer, History
```

## API Calls

- Never call `fetch` directly from components or pages. Add a wrapper in `src/api/<service>Api.js`.
- Every wrapper uses `fetchWithTimeout` from `apiUtils.js` (default `DEFAULT_TIMEOUT_MS = 10_000`). A timeout throws an `Error` with `status = 408`.
- Use relative `/api/v1/...` URLs. Routing to ports 8081/8082/8083 is done by the proxy in `vite.config.js`; add new path prefixes there.
- Authenticated calls spread `getAuthHeader()` into headers, call `handleUnauthorized()` on a 401, and throw `await parseApiError(res)` when `!res.ok` (handles non-JSON bodies, keeps `status`, copies RFC 7807 fields such as `code` and `detail`).
- `GET /api/v1/transactions` is paginated: `getTransactions(page, size)` returns `{items, page, size, totalPages, totalItems, hasMore}`.
- Monetary amounts are integers in minor units (cents). Convert only for display.

## Auth

- The JWT from `POST /api/v1/users/login` is stored in `sessionStorage` (`accessToken`, `userId`, `email`) via `AuthProvider.login()`; `clearAuthSession()` removes all three.
- Wrap protected routes in `<RequireAuth>`; it redirects to `/login` when there is no session.

## Error Handling

- The app root in `src/App.jsx` is wrapped in `react-error-boundary`'s `<ErrorBoundary FallbackComponent={AppErrorFallback}>`. Keep it as the outermost element; add nested boundaries for risky subtrees instead of removing it.
- Pages catch API errors and show a visible error message (`err.message` or a friendly fallback); do not swallow errors silently.

## CORS

- In dev the Vite proxy keeps requests same-origin, so CORS is not involved.
- When the frontend is served from another origin, set `CORS_ALLOWED_ORIGINS` (comma-separated, default `http://localhost:3000`) on all three services. It maps to `cors.allowed-origins` in each `application.yml` (user-service `SecurityConfig`, ledger/payment `WebConfig`).
