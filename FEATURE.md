# Feature: Multi-Google-Account Support

## Goal
Allow multiple family Gmail / Google Calendar accounts to be connected with full two-way sync (create, edit, delete events that write back to the correct calendar).

## Current State
- Secret iCal feeds already support any number of calendars (read-only or one-way).
- Full two-way OAuth currently supports only a single Google account (one set of `g_client_id`, `g_refresh`, etc.).

## Planned Changes
1. **GoogleCal.kt**
   - Store accounts as a JSON array in SharedPreferences (`g_accounts`).
   - Each account: `id`, `clientId`, `clientSecret`, `refreshToken`, `email`, `calendars[]`.
   - Methods to add/remove/list accounts, get access token per account, route writes.

2. **ConfigServer.kt + config.html**
   - UI to add a new Google account (enter client ID/secret, complete OAuth).
   - List connected accounts with email and option to disconnect.
   - Select which account to use when adding events.

3. **Writers.kt / SyncManager**
   - Route event create/delete to the correct Google account based on calendar ownership.

4. **Backward compatibility**
   - Migrate existing single-account prefs into the new array on first run.

5. **README**
   - Clear steps for connecting multiple family Gmail accounts.

## Status
Scaffold only — implementation in progress.
