# Public API (no login)

Both endpoints need no token, answer any origin (CORS `*`, GET only, no credentials), and are
cached in memory for `public-stats.cache-ttl` (default 5 minutes). Settings live under the
`public-stats` prefix (`PublicStatsProperties`).

## GET /api/public/rankings

```json
{ "updatedAt": "2026-10-11T03:00:00Z",
  "players": [ { "rank": 1, "name": "닉네임", "mmr": 1234, "wins": 40, "losses": 21, "draws": 2 } ] }
```

- 일반 사용자(id > 0)와 `bot_personas.enabled = true`인 봇을 MMR 내림차순, 승수 내림차순, 사용자 ID 오름차순으로 정렬해 상위 `ranking-size`(50)명을 반환한다.
- 봇 이름은 `bot_personas.name`을 사용하며, 계정 서버에서 조회하지 않는다.
- Needs at least `ranking-min-games` (5) finished PVP games with outcome WIN or DRAW; practice and abandoned games are not counted.
- 일반 사용자 이름은 계정 서버에서 조회한다. 조회에 실패한 사용자와 게스트 계정은 제외하고 순위 번호를 이어 붙인다. 게스트 제외에는 계정 서버의 `GET /api/members/{id}` 응답에 `guest` 필드가 필요하다.
- Email and user id are never returned.

## GET /api/public/statistics/magics

```json
{ "updatedAt": "...", "from": "...", "to": "...", "totalGames": 812,
  "magics": [ { "magicId": 12, "name": "...", "prefab": null, "decks": 300, "pickRate": 0.369, "winRate": 0.52, "casts": 4100 } ] }
```

- Window: the last `magic-window` (30 days). `totalGames` counts PVP games with outcome WIN or DRAW.
- `pickRate` = decks / distinct (game, real user) deck entries. `winRate` is `null` below `magic-min-sample` (20) WIN-outcome deck entries. `casts` sums `statistic_game_magics.count`.
- Sorted by `decks` desc; magics never picked are omitted. Guests are included (anonymous aggregate).
