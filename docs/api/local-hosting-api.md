# Local hosting API

The desktop client can run practice and adventure matches on a game server it starts on its own
machine. The lobby hands it the static data that server loads, issues the session, and checks and
records the result. The cross-repository contract is
`.plan/general/2026-10-12-local-hosting-contracts.md` in the monorepo root (sections 1 and 2).

Settings: `local-server.*` (`LocalServerProperties`), `local-sessions.*` (`LocalSessionProperties`),
`offline-pack.static-data-refresh-interval` (default `5m`).

## GET /api/offline-pack (no login)

Query `staticVersion` (optional): when it equals the current value, `staticData` is `null`.

```json
{ "schemaVersion": 1, "staticVersion": "<sha256 hex>",
  "staticData": { "schemaVersion": 1, "staticVersion": "<sha256 hex>", "generatedAt": "2026-10-12T03:00:00Z",
                  "tables": { "magics": { "columns": ["id", "name"], "rows": [[1, "fire_shot"]] } } },
  "localSessionsEnabled": true,
  "localServer": { "version": "1.3.0", "minimumVersion": "1.3.0",
                   "assets": { "windows-x64": "https://.../v1.3.0/local-server-windows-x64.zip", "macos-x64": "...", "macos-arm64": "...", "linux-x64": "..." } } }
```

- Tables and row filters are in `StaticDataTables`. `users` sends only bot rows (`id < 0`) and the
  columns `id, selected_deck_id, mmr, status, total_wins, novice_progress`; `decks` and `deck_cards`
  only what bots own. A listed table missing from the database is left out with a WARN log.
- Values are numbers, strings, booleans or null. `json`/`jsonb`, enums and other non-scalar types
  are text; arrays are a JSON string; `timestamp` is ISO-8601 without an offset, `timestamptz` is
  ISO-8601 UTC with `Z`.
- `staticVersion` is the sha256 of `tables` serialized in table-name order, with rows ordered by
  every column from the first.
- `localServer` is `null` while `local-server.version` is empty. An empty
  `local-server.minimum-version` means the same as `version`.

## POST /api/local-sessions

Body `{ "type": "PRACTICE" | "PVE", "scenarioId": 3 }` (`scenarioId` only for `PVE`).

- `201` with `sessionId` (`local-<n>`), `type`, `scenarioId`, `leftUserId`, `rightUserId`, `leftUser`,
  `rightUser`, `leftDeckCardIds` (selected deck, one magic id per card), `ownedMagicIds`,
  `noviceProgress`, `staticVersion`, `issuedAt`.
- Practice picks the bot like `GET /api/match/practice/me` (novice players meet the hospitality bot).
  `PVE` has `rightUserId` `-1` and the `dummy` right user.
- `400` malformed body, `409` scenario not `ACTIVE` or `FINISHED` for this user, `422` no selected
  deck, `503` when `local-sessions.enabled` is false.
- Redis keeps `local-session:{sessionId}` for `local-sessions.session-ttl` (2h).

## POST /api/local-sessions/{sessionId}/result

Body: `outcome` (`WIN`, `DRAW`, `ABANDONED`), `winUserId`, `lossUserId`, `durationMillis`,
`frameCount`, `tickRate`, `localServerVersion`, `decks` and `magics` (`[{userId, magicId, count}]`).

- `404` unknown or expired session, `409` already reported, `400` malformed body (the session stays open).
- Otherwise `200` `{ "accepted": true }` or `{ "accepted": false, "reason": "<code>" }`. Every
  verdict except `WRONG_USER` ends the session.

| reason | when |
|---|---|
| `WRONG_USER` | the caller is not the session's user (the session stays open) |
| `DURATION_EXCEEDS_WALL_CLOCK` | less time passed since issue than `durationMillis` |
| `FRAME_DURATION_MISMATCH` | `frameCount / tickRate` exceeds `durationMillis / 1000` by more than 10%, or `tickRate <= 0`. Fewer frames (a slow PC) is accepted |
| `TOO_SHORT` | a `WIN` shorter than `local-sessions.minimum-win-millis` (30000) |
| `PARTICIPANT_MISMATCH` | winner and loser are not the two participants (both `null` unless `WIN`), or a `decks`/`magics` row names someone else |
| `DAILY_LIMIT` | the user already has `local-sessions.daily-result-limit` (100) accepted results this UTC day |

An accepted result writes, in one transaction: `statistic_games` with `hosted_locally = true`,
`server_version = localServerVersion` and `game_type` `Practice` or `PVE`; `statistic_game_decks`
and `statistic_game_magics` (rows with an unknown magic id are dropped); on a user win
`users.total_wins + 1`, a `PVE` scenario `ACTIVE` to `FINISHED`, and after beating the hospitality
bot in practice `novice_progress + 0.15` (capped at 1.0). A rejection is stored in
`local_session_rejections` with the request body as `payload`.
