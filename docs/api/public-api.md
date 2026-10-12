# Public API (no login)

Both endpoints need no token, answer any origin (CORS `*`, GET only, no credentials), and are
cached in memory for `public-stats.cache-ttl` (default 5 minutes). Settings live under the
`public-stats` prefix (`PublicStatsProperties`).

## GET /api/public/rankings

```json
{ "updatedAt": "2026-10-11T03:00:00Z",
  "players": [ { "rank": 1, "name": "닉네임", "mmr": 1234, "wins": 40, "losses": 21, "draws": 2 } ] }
```

- Top `ranking-size` (50) real users (id > 0) by mmr desc, then wins desc, then user id asc.
- Needs at least `ranking-min-games` (5) finished PVP games with outcome WIN or DRAW; practice and abandoned games are not counted.
- Names come from the account server. Players whose lookup fails and guest accounts are skipped; ranks stay contiguous. Guest exclusion works only once the account server sends `guest` in `GET /api/members/{id}`.
- Email and user id are never returned.

## GET /api/public/statistics/magics

```json
{ "updatedAt": "...", "from": "...", "to": "...", "totalGames": 812,
  "magics": [ { "magicId": 12, "name": "...", "prefab": null, "decks": 300, "pickRate": 0.369, "winRate": 0.52, "casts": 4100 } ] }
```

- Window: the last `magic-window` (30 days). `totalGames` counts PVP games with outcome WIN or DRAW.
- `pickRate` = decks / distinct (game, real user) deck entries. `winRate` is `null` below `magic-min-sample` (20) WIN-outcome deck entries. `casts` sums `statistic_game_magics.count`.
- Sorted by `decks` desc; magics never picked are omitted. Guests are included (anonymous aggregate).
