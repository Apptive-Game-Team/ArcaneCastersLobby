package com.wordonline.matching.local.controller

import com.wordonline.matching.auth.service.UserId
import com.wordonline.matching.local.dto.LocalResultRequest
import com.wordonline.matching.local.dto.LocalSessionRequest
import com.wordonline.matching.local.service.LocalResultReportResult
import com.wordonline.matching.local.service.LocalSessionIssueResult
import com.wordonline.matching.local.service.LocalSessionService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * Sessions the desktop client hosts on its own game server.
 *
 * Error statuses are returned as [ResponseEntity] rather than thrown, because
 * `GlobalExceptionHandler` turns every unhandled exception into 500.
 */
@RestController
class LocalSessionController(
    private val localSessionService: LocalSessionService,
) {
    /**
     * 201 with the session; 400 for a malformed body; 409 for a scenario this user has not unlocked;
     * 422 without a selected deck; 503 while local sessions are switched off.
     */
    @PostMapping("/api/local-sessions")
    suspend fun issue(
        @UserId userId: Long?,
        @RequestBody(required = false) request: LocalSessionRequest?,
    ): ResponseEntity<Any> =
        when (val result = localSessionService.issue(userId!!, request ?: LocalSessionRequest())) {
            is LocalSessionIssueResult.Issued -> ResponseEntity.status(HttpStatus.CREATED).body(result.response)
            is LocalSessionIssueResult.InvalidRequest -> ResponseEntity.badRequest().body(result.message)
            LocalSessionIssueResult.Disabled ->
                ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("local sessions are disabled")
            LocalSessionIssueResult.ScenarioNotPlayable ->
                ResponseEntity.status(HttpStatus.CONFLICT).body("scenario is not unlocked for this user")
            LocalSessionIssueResult.NoSelectedDeck ->
                ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body("no deck is selected")
        }

    /**
     * 200 with `accepted` true or false and a `reason` when false; 400 for a malformed body; 404 for
     * an unknown or expired session; 409 for a session already reported.
     */
    @PostMapping("/api/local-sessions/{sessionId}/result")
    suspend fun report(
        @UserId userId: Long?,
        @PathVariable sessionId: String,
        @RequestBody(required = false) request: LocalResultRequest?,
    ): ResponseEntity<Any> =
        when (val result = localSessionService.report(userId!!, sessionId, request ?: LocalResultRequest())) {
            is LocalResultReportResult.Decided -> ResponseEntity.ok(result.response)
            is LocalResultReportResult.InvalidRequest -> ResponseEntity.badRequest().body(result.message)
            LocalResultReportResult.NotFound ->
                ResponseEntity.status(HttpStatus.NOT_FOUND).body("session $sessionId not found")
            LocalResultReportResult.AlreadyReported ->
                ResponseEntity.status(HttpStatus.CONFLICT).body("session $sessionId is already reported")
        }
}
