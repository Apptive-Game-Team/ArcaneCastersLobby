# Magic preview startup API

Authenticated clients use `GET /api/data/magic-previews` before any match.
It returns game-server status, revision and per-magic name/hash/byte-count/
availability, plus its registry `serverId`. No responsive game server returns
`status: unavailable`; generation in progress returns `status: generating`.
Responses include Retry-After: 5 and Cache-Control: no-store.

Download an available magic with
`GET /api/data/magic-previews/{name}?serverId=...&revision=...&hash=...`.
The ID must remain in this lobby's healthy registry. Supplied addresses are never
accepted and failures never retry against another deployment. The game server
verifies revision; this lobby verifies the actual UTF-8 content hash. Revision/hash
mismatch is 409; unavailable magic is 404; absent server/upstream failure is 503.
Bodies are existing compact v2 replay JSON.

The cloned WebClient builder retains service Authorization. Requests have a
10-second bound and 4 MiB decode limit (`preview.timeout`, `preview.max-clip-bytes`).
Caller cancellation propagates; the request's own timeout becomes unavailable.
This API never registers sessions, queries user match recovery or simulates a game.

Deploy after game-server #102 and before client #266. Tests cover catalog routing,
pinning, digests, missing server, timeout, revision conflict and cancellation.
