# Development magic playground

Set PLAYGROUND_ENABLED=true on both development lobby and game servers.
Default false leaves the route unregistered. Administrator user tokens can
POST /api/dev/playgrounds without a request body. Owner identity is taken from
the authenticated memberId; it cannot be specified by the client.

Lobby offers the session to available servers using its service-token WebClient.
Only a game server exposing POST /api/server/playgrounds can accept it.
The result contains sessionId, server, webSocketUrl, ownerId and expiresAt.

The owner controls the game server directly through /api/dev/playgrounds/{id}:
POST /cast, /clear, /immunity; GET /snapshot; DELETE to close.
The client subscribes to /game/{sessionId}/frameInfos/{ownerId}.
The server removes the session after 300 seconds without requiring heartbeats.
No lobby ticket, recovery record, deck validation, synthetic account or ordinary
user status is created. This stateless flow needs no migration or expiration job.

When changing this flow, do not delegate to ordinary createSession: it looks up
two accounts and writes SessionRecoveryStore, which would overwrite the
developer's ordinary game recovery information.
