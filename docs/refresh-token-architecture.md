# JWT Session Management: Refresh Token Architecture

**Issue:** [#94 — Refresh token rotation + seamless session renewal](https://github.com/sergey-pi/neobank-workshop/issues/94)  
**Depends on:** PR #83 (IDOR fix), PR #93 (JWT revocation + 15-min expiry)

---

## Problem

Short-lived access tokens (15 min) protect against token theft but break session continuity. A user actively transferring funds gets a 401 mid-flow and is forced to re-login. This document describes the dual-token pattern that solves both problems.

---

## Token Design

| Property | Access Token | Refresh Token |
|---|---|---|
| Format | JWT (signed HS256) | Opaque UUID |
| TTL | 15 minutes | 7 days |
| Storage (client) | JS memory (`useRef` / React context) | `HttpOnly Secure SameSite=Strict` cookie |
| Storage (server) | Stateless (Redis blacklist for revocation) | PostgreSQL `refresh_tokens` table (SHA-256 hash) |
| Transport | `Authorization: Bearer <token>` header | Cookie (automatic, no JS access) |

**Why not localStorage for access tokens?** XSS can read localStorage. JS memory is cleared on tab close — acceptable trade-off since refresh tokens handle persistence.

**Why HttpOnly cookie for refresh tokens?** JS cannot read HttpOnly cookies, so XSS cannot steal them. `SameSite=Strict` mitigates CSRF.

---

## Full Session Lifecycle

```
┌──────────┐         POST /api/v1/auth/login
│  Browser │─────────────────────────────────────────────► user-service
│          │◄─────────────────────────────────────────────
│          │   Body: { accessToken, expiresIn: 900 }
│          │   Cookie: refresh_token=<opaque>; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth/refresh
│          │
│          │         GET /api/v1/accounts  (Authorization: Bearer <accessToken>)
│          │─────────────────────────────────────────────► ledger-service
│          │◄─────────────────────────────────────────────  200 OK
│          │
│  [15 min later — access token expires]
│          │
│          │         GET /api/v1/accounts  (expired token)
│          │─────────────────────────────────────────────► ledger-service
│          │◄─────────────────────────────────────────────  401 Unauthorized
│          │
│ Axios    │         POST /api/v1/auth/refresh  (cookie sent automatically)
│ intercep.│─────────────────────────────────────────────► user-service
│          │◄─────────────────────────────────────────────
│          │   Body: { accessToken }
│          │   Cookie: refresh_token=<new-opaque>; ...  ← rotated
│          │
│          │         GET /api/v1/accounts  (new access token, retried)
│          │─────────────────────────────────────────────► ledger-service
│          │◄─────────────────────────────────────────────  200 OK (user never noticed)
└──────────┘
```

---

## Database Schema

```sql
-- Migration: V{N}__add_refresh_tokens.sql (user-service)
CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  TEXT NOT NULL UNIQUE,   -- SHA-256(raw_token), never store raw
    family_id   UUID NOT NULL,          -- groups rotated tokens for reuse detection
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked     BOOLEAN NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_tokens_user_id  ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_family   ON refresh_tokens(family_id);
CREATE INDEX idx_refresh_tokens_expires  ON refresh_tokens(expires_at)
    WHERE revoked = false;   -- partial index for cleanup job
```

**Never store the raw token** — only `SHA-256(token)`. If the DB is compromised, tokens cannot be extracted.

---

## Refresh Token Rotation + Reuse Detection

Every successful `/auth/refresh` call:
1. Validates the presented token (hash lookup, not expired, not revoked)
2. **Revokes** the old token (sets `revoked = true`)
3. **Issues a new token** in the same `family_id`
4. Returns new access token + sets new refresh cookie

**Reuse detection:** If a token that was already rotated is presented again, it means the old token was stolen and used. The server:
1. Detects the revoked token presentation
2. Revokes **all tokens in the same family** (full family invalidation)
3. Returns 401 — user must re-login

```
Normal rotation:
  token-A (valid) → refresh → token-A revoked, token-B issued (same family)

Theft scenario:
  Attacker uses stolen token-A after legitimate user already got token-B:
  token-A presented → revoked → ALERT: revoke entire family → both users kicked out
```

---

## API Endpoints

### `POST /api/v1/auth/login`

**Request:**
```json
{ "email": "user@example.com", "password": "secret" }
```

**Response:**
```json
{ "accessToken": "<jwt>", "expiresIn": 900, "tokenType": "Bearer" }
```
+ `Set-Cookie: refresh_token=<uuid>; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth/refresh; Max-Age=604800`

---

### `POST /api/v1/auth/refresh`

Cookie `refresh_token` sent automatically by browser.  
No request body required.

**Response (success):**
```json
{ "accessToken": "<new-jwt>", "expiresIn": 900, "tokenType": "Bearer" }
```
+ New rotated `Set-Cookie`

**Response (invalid/expired/reused):** `401 Unauthorized` — clear cookie, redirect to login

---

### `POST /api/v1/auth/logout`

**Request header:** `Authorization: Bearer <accessToken>`  
Cookie sent automatically.

**Actions:**
1. Blacklist access token `jti` in Redis (TTL = remaining lifetime)
2. Revoke refresh token in DB
3. Clear cookie: `Set-Cookie: refresh_token=; Max-Age=0; HttpOnly; ...`

**Response:** `204 No Content`

---

## Backend Implementation (user-service)

### New service: `RefreshTokenService`

```java
@Service
@Transactional
public class RefreshTokenService {

    public String createRefreshToken(UUID userId) {
        String raw = UUID.randomUUID().toString();
        String hash = sha256(raw);
        UUID familyId = UUID.randomUUID();
        // insert into refresh_tokens
        return raw;   // return to caller for cookie
    }

    public UUID validateAndRotate(String rawToken) {
        String hash = sha256(rawToken);
        RefreshToken token = dsl.selectFrom(REFRESH_TOKENS)
            .where(REFRESH_TOKENS.TOKEN_HASH.eq(hash))
            .fetchOne();

        if (token == null || token.getRevoked() || token.getExpiresAt().isBefore(now())) {
            throw new UnauthorizedException("Invalid refresh token");
        }

        if (token.getRevoked()) {
            // Reuse detected — revoke entire family
            revokeFamily(token.getFamilyId());
            throw new UnauthorizedException("Refresh token reuse detected");
        }

        // Rotate: revoke old, issue new in same family
        dsl.update(REFRESH_TOKENS).set(REFRESH_TOKENS.REVOKED, true)
           .where(REFRESH_TOKENS.ID.eq(token.getId())).execute();

        String newRaw = UUID.randomUUID().toString();
        // insert new token with same family_id
        return token.getUserId();
    }
}
```

### Scheduled cleanup

```java
@Scheduled(cron = "0 0 3 * * *")   // 3 AM daily
public void purgeExpiredTokens() {
    dsl.deleteFrom(REFRESH_TOKENS)
       .where(REFRESH_TOKENS.EXPIRES_AT.lt(OffsetDateTime.now()))
       .execute();
}
```

---

## Frontend Implementation (React)

### Token storage

```typescript
// AuthContext.tsx — access token in memory only
const [accessToken, setAccessToken] = useState<string | null>(null);
// Never: localStorage.setItem('token', ...)
```

### Axios interceptor

```typescript
// api/client.ts
let isRefreshing = false;
let failedQueue: Array<{ resolve: Function; reject: Function }> = [];

axiosInstance.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config;

    if (error.response?.status === 401 && !originalRequest._retry) {
      if (isRefreshing) {
        // Queue requests while refresh is in progress
        return new Promise((resolve, reject) => {
          failedQueue.push({ resolve, reject });
        }).then((token) => {
          originalRequest.headers['Authorization'] = `Bearer ${token}`;
          return axiosInstance(originalRequest);
        });
      }

      originalRequest._retry = true;
      isRefreshing = true;

      try {
        const { data } = await axiosInstance.post('/api/v1/auth/refresh');
        const newToken = data.accessToken;
        setAccessToken(newToken);                          // update context
        failedQueue.forEach(({ resolve }) => resolve(newToken));
        originalRequest.headers['Authorization'] = `Bearer ${newToken}`;
        return axiosInstance(originalRequest);
      } catch {
        failedQueue.forEach(({ reject }) => reject());
        clearAuth();                                       // logout user
        return Promise.reject(error);
      } finally {
        isRefreshing = false;
        failedQueue = [];
      }
    }

    return Promise.reject(error);
  }
);
```

**Key detail:** `isRefreshing` + `failedQueue` prevents a thunderstorm of concurrent refresh calls when multiple requests expire simultaneously.

---

## Security Summary

| Threat | Mitigation |
|---|---|
| XSS steals access token | Stored in JS memory (not localStorage/cookie), short 15-min window |
| XSS steals refresh token | HttpOnly cookie — JS cannot read it |
| CSRF on /auth/refresh | SameSite=Strict cookie; requires Bearer header on all state-changing endpoints |
| Refresh token theft | Reuse detection → family revocation; max 7-day exposure window |
| Compromised user | Delete refresh_tokens row → kicked out within ≤15 min |
| DB breach | Only SHA-256 hashes stored; raw tokens not recoverable |

---

## Implementation Checklist

- [ ] Flyway migration: `refresh_tokens` table (user-service)
- [ ] `RefreshTokenService`: create, validate, rotate, revoke family
- [ ] `AuthController.login()`: set HttpOnly cookie alongside access token
- [ ] `POST /api/v1/auth/refresh` endpoint
- [ ] `POST /api/v1/auth/logout`: revoke both tokens
- [ ] Scheduled cleanup of expired tokens
- [ ] `SecurityConfig`: permit `/api/v1/auth/refresh` and `/api/v1/auth/logout`
- [ ] Frontend: move access token to memory (remove from localStorage if present)
- [ ] Frontend: Axios interceptor with queued retry
- [ ] Integration test: login → wait → refresh → verify original request succeeds
- [ ] Integration test: reuse detection revokes family
