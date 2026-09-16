# Repository Guidelines

## Project Structure & Module Organization

This repository is `WordOnlineMatching`, the lobby and matchmaking server for Word Online. Source code lives under `src/main/java/com/wordonline/matching` and `src/main/kotlin/com/wordonline/matching`; both Java and Kotlin are active. Feature packages include `auth`, `matching`, `session`, `server`, `deck`, `magic`, `quest`, `adventure`, `data`, `decoration`, `deploy`, and `global`. Tests are in `src/test/java`. Runtime configuration and SQL helpers are in `src/main/resources`, while API notes are in `docs/api`.

Related repositories:
- `Apptive-Game-Team/WordOnlineServer`: actual game session server.
- `Apptive-Game-Team/WordOnlineClient`: Unity client consuming these APIs.
- `Apptive-Game-Team/AccountServer`: account/member service used by `AccountClient`.
- `Apptive-Game-Team/WordOnlineDatabase`: database migration and operational
  SQL source of truth.

## Build, Test, and Development Commands

- `./gradlew compileJava compileKotlin`: compile Java and Kotlin sources.
- `./gradlew test`: run JUnit 5 tests, including WebFlux and Reactor tests.
- `./gradlew bootRun`: run the Spring Boot app locally.
- `./gradlew bootJar`: create the deployable Spring Boot jar.

Local runs require environment variables from `application.yml`: `PORT`, `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, and `ACCOUNT_SERVER_URL`. The lobby verifies every JWT against the account server's JWK Set at `ACCOUNT_SERVER_URL` + `/.well-known/jwks`, so it holds no signing key of its own. For schema questions, inspect `../database/migration`.

## Coding Style & Naming Conventions

Use package-by-feature organization. Keep controllers thin, business logic in services, and database access in repositories. Use 4-space indentation and descriptive DTO names such as `MatchedInfoDto` or `DeployStatusResponse`.

### Kotlin and coroutines first

New code is Kotlin with coroutines. Do not add new Java classes; put new files under `src/main/kotlin`.

- Write logic as `suspend` functions instead of assembling Reactor chains (`flatMap`, `switchIfEmpty`, `zip`). Accept `Mono`/`Flux` only at the Spring Data R2DBC and `WebClient` boundary, then cross into coroutines immediately with `awaitSingle()`, `awaitSingleOrNull()`, or `asFlow()`. Do not re-wrap a coroutine result back into a Reactor type.
- Controller handlers are `suspend fun`.
- Use `coroutineScope { async { } }` for concurrent work so cancellation propagates. Never use `GlobalScope`.
- Wrap blocking calls in `withContext(Dispatchers.IO)`.
- Background scheduled work runs on `CoroutineScope(SupervisorJob() + Dispatchers.Default)` with a `CoroutineExceptionHandler`, so one failure cannot tear down the scope. `@Scheduled` cannot invoke a `suspend` function, so the annotated method stays a plain function that launches into that scope.
- Rethrow `CancellationException` before catching broader exceptions.
- Test coroutine code with `runTest`. A `@Test` method must return `Unit`; a trailing expression makes the method non-void and JUnit 5 silently skips it, so write `runBlocking<Unit> { }` or annotate the return type. After adding tests, confirm they actually ran in `build/test-results/test`.
- Lombok-generated accessors are invisible to the Kotlin compiler, which compiles first. When Kotlin needs to read a Lombok-annotated Java entity, convert that entity to Kotlin rather than adding Lombok compiler plugins.
- When a change effectively rewrites an existing Java file, port it to Kotlin. Leave files that only need a line or two alone, and do not bulk-refactor beyond the task scope.

### Configuration values

Do not inject individual settings with `@Value`. Group related settings into one `@ConfigurationProperties` class and constructor-inject that object.

```kotlin
@ConfigurationProperties(prefix = "gameserver")
data class GameServerProperties(
    val refreshInterval: Duration = Duration.ofSeconds(15),
    val failureThreshold: Int = 3,
)
```

Properties classes are picked up by `@ConfigurationPropertiesScan` on `Application`. Define each default in one place. Annotation arguments that require a string literal, such as `@Scheduled(fixedDelayString = "\${...}")`, are the only exception.

## Testing Guidelines

Tests use JUnit 5, Spring Boot Test, Spring Security Test, and Reactor Test. Name tests by behavior; existing tests include Korean display-style method names. Add focused tests for controller response shapes, service branching, and repository queries when behavior changes. Run `./gradlew test` before handoff.

## Commit & Pull Request Guidelines

Recent history uses short conventional-style prefixes such as `feat:`, `fix:`, and `refactor(...)`, often with linked issue numbers like `(#36)`. Keep commits scoped to one concern. PRs should describe behavior changes, link the GitHub issue, mention affected endpoints, and note any required database migration or client/game-server/account-server coordination.

Workflow for tracked work in this repo:
- Create a GitHub issue before implementation when the user asks for end-to-end delivery.
- Create and work on a dedicated branch per issue.
- Use branch names in the form `<issue-label>/<issue-num>` such as `feature/123` or `fix/39`.
- After implementation and verification, open a PR linked to the issue.

Every issue and pull request must set an assignee and a label. Do not leave either blank.

- Assignee: `--assignee @me`.
- Label: use the same value as the branch prefix, so branch `fix/80` carries label `fix`. Check the available labels with `gh label list`; this repo has `fix`, `feature`, `documentation`, and `bug`. Do not invent new labels — ask when none of them fit.
- Do not attach a project.

```bash
gh issue create --title "..." --body "..." --assignee @me --label fix
gh pr create --base <base> --title "..." --body "..." --assignee @me --label fix
```

Confirm both landed with `gh issue view <n> --json assignees,labels` after creating.

## Security & Configuration Tips

Do not commit secrets or local `.env` values. JWT keys, database credentials,
Redis settings, and account server URLs must remain environment-driven.
Database schema, seed, and operational data changes belong in
`WordOnlineDatabase`. Do not add production SQL to this repository's runtime
resources. Test-only fixtures may remain with their tests.

## Versioning

`version` in `build.gradle` is the lobby server's single version source.
Do not bump it in a pull request. The monorepo `deploy` skill bumps it once per
promotion: it commits `chore(release): WordOnlineMatching vX.Y.Z` to `main`, merges
`main` into `deploy`, then tags and releases `vX.Y.Z` on the merge commit. The level comes
from the Conventional Commit messages promoted in that release: MAJOR for a `!`
marker or a `BREAKING CHANGE` trailer, MINOR for `feat:`, PATCH otherwise, so
write accurate commit types.

Never add a second runtime version or use a `-SNAPSHOT` deployable version.
Spring Boot build info embeds this value.
