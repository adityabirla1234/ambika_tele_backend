# Staff login replaces Cloudflare Access

Staff now sign in with an email and password that the app checks itself. Cloudflare Access is no longer used or needed.
This package is your `src/` folder with the change applied. Replace your `src/` with it.

> **Do this first, before anything else: change your Aiven database password.**
> `application-dev.yml` in the zip you sent contained the live database user and password in plain text. Anyone who has that
> file (the zip, a git repository, a chat) can reach your database. Change the password in the Aiven console, then put the
> new one only in `backend/.env` (see "Settings" below). The copy of `application-dev.yml` in this package no longer has it,
> but that does not undo the exposure of the old one. If the file was ever committed to git, the old password is still in the history.

## What was not checked

There is no Maven, no Spring libraries and no database where this was written, so **the project was never built or run**.
What was checked: every new or changed file parses as Java (a deliberately broken copy was caught by the same check), and the
password rules were compiled and run against 12 cases. Everything else (Spring wiring, the database columns, a real sign-in)
is untested. Expect to fix a compile error or two. The manual test list at the end is how to confirm it works.

## What changed

**Removed:** `JwtConfig`, `DevUserFilter`, `StaffAccessService`, `OriginCheckFilter`, the Cloudflare and `X-Dev-User` settings.

**Added (all in `security/`):**
- `StaffAuthController`: `POST /api/staff/auth/login`, `GET /me`, `POST /logout`, `POST /change-password`
- `StaffAuthService`: sign-in, session check, sign-out, password change, lockout
- `StaffTokenFilter`: turns `Authorization: Bearer <token>` into a signed-in user on each request
- `StaffSession` and its repository: the `staff_sessions` table
- `PasswordPolicy`: the password rules

**Changed:** `StaffUser` (password hash, lockout and login columns), `StaffUserRepository`, `StaffBootstrap`, `SecurityConfig`,
`RateLimitFilter` (two new rules), `application.yml`, `application-dev.yml`. Controllers did not change: they only ever
used `auth.getName()` for the staff email, and that is still the email.

## How it works

- **Passwords** are stored as BCrypt hashes (cost 12). 12 to 72 characters; a short list of too-easy words is refused; the email
  name may not be inside it. Not checked: breached-password lists (that needs an outside service).
- **Sign-in** returns a random 256-bit token. Only its SHA-256 is stored in `staff_sessions`, so a copy of the table cannot be used
  to sign in. A session ends after **30 idle minutes**, after **12 hours**, on sign-out, when the user is switched off, or when the
  password changes (the other browsers are signed out, the current one stays).
- **Roles come from the database on every request.** Removing a role or setting `active = 0` takes effect immediately.
- **One answer for every failure.** Unknown email, wrong password, switched-off account and locked account all return the same
  message, and an unknown email still pays for one BCrypt check, so timing does not show which emails exist.
- **Lockout:** 5 wrong passwords in a row lock that account for 15 minutes. Counters use atomic UPDATEs, so simultaneous guesses all count.
- **Rate limits** (per IP): 10 sign-ins and 10 password changes per 10 minutes. There is no limit across all clients on purpose
  (see trade-offs).
- **First accounts** come from settings. An account is only created when both its email and password are set, the password is only used
  on an account that has none (leaving the setting in place can never reset anything), and the account **must choose a new password**
  at first sign-in. Until then every other staff route answers `PASSWORD_CHANGE_REQUIRED`.
- **No default password exists anywhere**, including the dev profile.
- Audit log entries: sign-in, failed sign-in (known accounts only), lockout, sign-out, password change. Passwords and tokens are never logged.

## Settings

Render (production), replacing the old `CF_ACCESS_*` and `BOOTSTRAP_*_EMAILS` settings, which you can delete:

| Setting | Meaning |
|---|---|
| `BOOTSTRAP_CLAIM_ADMIN_EMAIL`, `BOOTSTRAP_CLAIM_ADMIN_PASSWORD` | the shop owner's first account |
| `BOOTSTRAP_PRODUCT_MANAGER_EMAIL`, `BOOTSTRAP_PRODUCT_MANAGER_PASSWORD` | your first account |
| `STAFF_SESSION_IDLE_MINUTES` (30), `STAFF_SESSION_MAX_HOURS` (12), `BCRYPT_STRENGTH` (12) | optional |

After both people have signed in and chosen their own passwords, **delete the two `*_PASSWORD` settings.**
Staff emails must be at most 80 characters (the audit log column is 80).

Local: `backend/.env` (already read by `application.yml`, keep it out of git):
```
DB_URL=jdbc:mysql://<host>:<port>/<database>?ssl-mode=REQUIRED
DB_USER=<user>
DB_PASSWORD=<the NEW password>
BOOTSTRAP_CLAIM_ADMIN_PASSWORD=<12+ characters>
BOOTSTRAP_PRODUCT_MANAGER_PASSWORD=<12+ characters>
```
The dev profile defaults the two emails to `owner@ambika.local` and `manager@ambika.local`.

`pom.xml` was not in your zip. You can remove `spring-boot-starter-oauth2-resource-server`; it is now unused and harmless if left.

## Database

`ddl-auto=update` will add columns to `users` (`password_hash`, `must_change_password`, `failed_attempts`, `locked_until`,
`password_changed_at`, `last_login_at`) and create `staff_sessions`. Existing users keep their roles and get a password from the
settings above on the next start. As always with `update`: back up first.

## Decisions you should know about

1. **A header token, not a cookie.** Your frontend (Vercel) and API (Render) are on different domains. A login cookie there is a
   "third-party cookie", which Safari and a growing number of Chrome setups block, so cookie sign-in would fail for some staff. The
   token lives in the browser's `sessionStorage` (gone when the tab closes). The cost: a script injected into the page could read it.
   React escapes what it renders, but also add a Content-Security-Policy header on the Vercel site.
2. **`OriginCheckFilter` was deleted.** It existed because a Cloudflare cookie is attached to requests automatically, so a forged
   cross-site POST looked signed in. A header token is not attached automatically, so that attack no longer exists. CORS also stops
   allowing credentials. If you want the filter back as extra caution, it is in your original zip.
3. **Lockout can be abused.** Someone who knows the owner's email can send 5 wrong passwords every 15 minutes and keep the
   account locked. The per-IP limit slows this and the owner can still sign in between lock periods once it stops, but it is a real
   trade-off of any lockout. I left out a limit across all clients so flooding cannot lock out everyone.
4. **No "forgot password".** There is no email or SMS sending in V1, so there is nothing safe to send a reset link through. If a
   password is lost, it needs a one-off fix by whoever runs the database. Decide how you want this handled (see below).
5. **No screen to add or switch off staff.** Accounts come only from the settings above. To switch someone off, set
   `active = 0` on their `users` row. Say if you want a management screen; I did not invent one.
6. **No second factor** (authenticator app or code). Email and password only, as requested.

## Still to do on your side

- Rotate the Aiven password (top of this file).
- Any README or launch checklist text that mentions Cloudflare Access (Phase 8) is now out of date.
- Frontend: see `FRONTEND_NOTES.md` in the other package.

## Manual test list (there is no `src/test`)

With the backend running, using curl or the browser:
1. Start with an owner password set: first sign-in works and the response has `"mustChangePassword": true`.
2. With that temporary token, `GET /api/admin/claims` answers 403 `PASSWORD_CHANGE_REQUIRED`; `POST /api/staff/auth/change-password` works.
3. Weak new passwords are refused: 11 characters, `MyPassword2026!!`, the email name, 73 bytes.
4. After changing it, the claim list loads. A second browser that was signed in earlier is now signed out.
5. Wrong password 5 times: the 6th attempt with the RIGHT password is also refused. After 15 minutes it works.
6. The messages for an unknown email, a wrong password and a locked account are identical.
7. Owner token on `/api/product-manager/**` answers 403; manager token on `/api/admin/**` answers 403.
8. Sign out, then reuse the old token: 401.
9. No `Authorization` header on a staff route: 401. A `Cf-Access-Jwt-Assertion` header no longer does anything.
10. Set `active = 0` for a signed-in user: their next request is 401.
11. Eleven sign-in attempts in 10 minutes from one address: the eleventh answers 429.
12. `staff_sessions` contains 64-character hashes, never the token you were given. The logs contain no passwords or tokens.
