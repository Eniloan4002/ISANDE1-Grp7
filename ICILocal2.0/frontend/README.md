# ICI Local 2.0 React foundation

This app reserves the prototype's screen paths for later React migrations. The previous static site remains in [`../../prototype/`](../../prototype/index.html).

## Run

```sh
cd ICILocal2.0/frontend
npm install
npm run dev
```

Use `npm test` for foundation checks and `npm run build` for a production build. Set `VITE_API_BASE_URL` in `.env.local` if the API is not under `/api`.

## Auth handoff

The sign-in page is a placeholder. When the login screen and backend are ready, call `setAccessToken(jwt)` from `useAuth()` after successful sign-in. The provider keeps it in memory, so refreshing the page requires sign-in again. `logout()` clears it. The shared Axios `api` instance adds `Authorization: Bearer <token>` and clears auth on HTTP 401.

The claims adapter in `src/auth/jwt.js` currently accepts a `roles` array or a single `role` string containing these prototype role keys: `student`, `registrar`, `adviser`, `teacher`, `guidance`, `admin`. Tokens must have a future numeric `exp` claim; `nbf` is respected when present. Confirm the backend's claim names and role values before connecting login, then adjust that one adapter if needed. Client-side claim decoding only drives the interface: the backend must validate JWT signatures and enforce permissions on every protected API endpoint.

Route paths omit `.html`, for example `/enrollment/queue`. The deployment server must serve `ICILocal2.0/frontend/dist/index.html` for unknown paths so direct links resolve through React Router. `src/routes/routeConfig.js` is the shared source for route access and navigation.
