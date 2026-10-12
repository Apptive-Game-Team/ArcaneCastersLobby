package com.wordonline.matching.local.service

import com.wordonline.matching.auth.dto.UserDetailResponseDto
import com.wordonline.matching.auth.service.UserService
import com.wordonline.matching.local.config.LocalSessionProperties
import com.wordonline.matching.local.domain.LocalSessionRecord
import com.wordonline.matching.local.domain.LocalSessionState
import com.wordonline.matching.local.domain.LocalSessionType
import com.wordonline.matching.local.dto.LocalResultCount
import com.wordonline.matching.local.dto.LocalResultRequest
import com.wordonline.matching.local.dto.LocalResultResponse
import com.wordonline.matching.local.dto.LocalSessionRequest
import com.wordonline.matching.local.dto.StaticData
import com.wordonline.matching.local.repository.AcceptedLocalResult
import com.wordonline.matching.local.repository.LocalSessionRepository
import com.wordonline.matching.local.repository.LocalSessionStore
import com.wordonline.matching.local.repository.LocalSessionTransition
import com.wordonline.matching.local.repository.StatisticCount
import com.wordonline.matching.matching.repository.MatchingQueueRepository
import com.wordonline.matching.matching.service.BotMemberMaker
import com.wordonline.matching.matching.service.PracticeOpponentSelector
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class LocalSessionServiceTest {

    private val now = Instant.parse("2026-10-12T03:10:00Z")
    private val issuedAt = Instant.parse("2026-10-12T03:00:00Z")
    private val userId = 5L
    private val hospitalityBotId = -54L
    private val ordinaryBotId = -60L

    private val store: LocalSessionStore = mock()
    private val repository: LocalSessionRepository = mock()
    private val matchingQueueRepository: MatchingQueueRepository = mock()
    private val userService: UserService = mock()
    private val botMemberMaker: BotMemberMaker = mock()
    private val staticDataService: StaticDataService = mock()

    private fun service(properties: LocalSessionProperties = LocalSessionProperties()) = LocalSessionService(
        properties,
        store,
        repository,
        matchingQueueRepository,
        PracticeOpponentSelector(botMemberMaker, userService),
        userService,
        staticDataService,
        Jackson2ObjectMapperBuilder.json().build(),
        Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun session(
        type: LocalSessionType = LocalSessionType.PRACTICE,
        hospitalityBot: Boolean = false,
        rightUserId: Long = if (type == LocalSessionType.PVE) -1L else ordinaryBotId,
        scenarioId: Long? = if (type == LocalSessionType.PVE) 3L else null,
    ) = LocalSessionRecord("local-1", userId, type, scenarioId, rightUserId, hospitalityBot, issuedAt, LocalSessionState.ISSUED)

    private fun win(
        winUserId: Long? = userId,
        lossUserId: Long? = ordinaryBotId,
        durationMillis: Long = 154_000,
        frameCount: Long = 9_240,
        tickRate: Int = 60,
    ) = LocalResultRequest(
        outcome = "WIN",
        winUserId = winUserId,
        lossUserId = lossUserId,
        durationMillis = durationMillis,
        frameCount = frameCount,
        tickRate = tickRate,
        localServerVersion = "1.3.0",
        decks = listOf(LocalResultCount(userId, 1, 3)),
        magics = listOf(LocalResultCount(userId, 1, 4)),
    )

    private suspend fun givenSession(record: LocalSessionRecord) {
        whenever(store.find("local-1")).thenReturn(record)
        whenever(store.transition("local-1", LocalSessionState.ISSUED, LocalSessionState.REPORTED))
            .thenReturn(LocalSessionTransition.TRANSITIONED)
        whenever(store.incrementDailyAccepted(eq(userId), any())).thenReturn(1L)
        whenever(repository.recordAccepted(any())).thenReturn(77L)
    }

    private fun rejected(reason: String) = LocalResultReportResult.Decided(LocalResultResponse(false, reason))
    private val accepted = LocalResultReportResult.Decided(LocalResultResponse(true))

    // Issue

    private suspend fun givenIssuable() {
        whenever(repository.findSelectedDeckCardIds(userId)).thenReturn(List(15) { 1L })
        whenever(repository.findOwnedMagicIds(userId)).thenReturn(listOf(1L, 7L))
        whenever(repository.findNoviceProgress(userId)).thenReturn(0.5)
        whenever(matchingQueueRepository.nextSessionId()).thenReturn(Mono.just(123L))
        whenever(staticDataService.current()).thenReturn(StaticData(1, "abc", now, emptyMap()))
        whenever(userService.getUserDetail(userId)).thenReturn(Mono.just(UserDetailResponseDto(userId, "me", "me@example.com")))
        whenever(userService.getUserDetail(hospitalityBotId)).thenReturn(Mono.just(UserDetailResponseDto(hospitalityBotId, "host", "bot@theevilent.com")))
    }

    @Test
    fun `초보자의 연습 세션은 접대봇과 붙고 그 사실이 Redis 기록에 남는다`() = runBlocking<Unit> {
        givenIssuable()
        whenever(userService.isNovice(userId)).thenReturn(Mono.just(true))
        whenever(botMemberMaker.getHospitalityBotId()).thenReturn(Mono.just(hospitalityBotId))

        val result = service().issue(userId, LocalSessionRequest("PRACTICE"))

        val response = (result as LocalSessionIssueResult.Issued).response
        assertThat(response.sessionId).isEqualTo("local-123")
        assertThat(response.rightUserId).isEqualTo(hospitalityBotId)
        assertThat(response.rightUser.name).isEqualTo("host")
        assertThat(response.leftDeckCardIds).hasSize(15)
        assertThat(response.ownedMagicIds).containsExactly(1L, 7L)
        assertThat(response.staticVersion).isEqualTo("abc")
        val saved = argumentCaptor<LocalSessionRecord>()
        verifyBlocking(store) { save(saved.capture()) }
        assertThat(saved.firstValue.hospitalityBot).isTrue()
        assertThat(saved.firstValue.state).isEqualTo(LocalSessionState.ISSUED)
        verifyBlocking(botMemberMaker, never()) { getRandomEnabledBotId() }
    }

    @Test
    fun `PVE 세션은 INACTIVE 시나리오에 409 다`() = runBlocking<Unit> {
        givenIssuable()
        whenever(repository.findScenarioState(userId, 3L)).thenReturn("INACTIVE")

        assertThat(service().issue(userId, LocalSessionRequest("PVE", 3L))).isEqualTo(LocalSessionIssueResult.ScenarioNotPlayable)
        verifyBlocking(store, never()) { save(any()) }
    }

    @Test
    fun `PVE 세션은 오른쪽이 -1 이고 dummy 사용자다`() = runBlocking<Unit> {
        givenIssuable()
        whenever(repository.findScenarioState(userId, 3L)).thenReturn("FINISHED")

        val response = (service().issue(userId, LocalSessionRequest("PVE", 3L)) as LocalSessionIssueResult.Issued).response

        assertThat(response.rightUserId).isEqualTo(-1L)
        assertThat(response.rightUser).isEqualTo(UserDetailResponseDto(0L, "dummy", "dummy@team6515.com"))
        assertThat(response.scenarioId).isEqualTo(3L)
    }

    @Test
    fun `로컬 세션이 꺼져 있으면 아무것도 읽지 않고 Disabled 다`() = runBlocking<Unit> {
        val result = service(LocalSessionProperties(enabled = false)).issue(userId, LocalSessionRequest("PRACTICE"))

        assertThat(result).isEqualTo(LocalSessionIssueResult.Disabled)
        verifyBlocking(matchingQueueRepository, never()) { nextSessionId() }
    }

    // Report

    @Test
    fun `없는 세션은 NotFound 다`() = runBlocking<Unit> {
        assertThat(service().report(userId, "local-1", win())).isEqualTo(LocalResultReportResult.NotFound)
    }

    @Test
    fun `이미 보고된 세션은 AlreadyReported 다`() = runBlocking<Unit> {
        givenSession(session())
        whenever(store.transition("local-1", LocalSessionState.ISSUED, LocalSessionState.REPORTED))
            .thenReturn(LocalSessionTransition.STATE_MISMATCH)

        assertThat(service().report(userId, "local-1", win())).isEqualTo(LocalResultReportResult.AlreadyReported)
        verifyBlocking(repository, never()) { recordAccepted(any()) }
    }

    @Test
    fun `다른 사용자의 보고는 WRONG_USER 로 거절하고 세션은 열어 둔다`() = runBlocking<Unit> {
        givenSession(session())

        assertThat(service().report(99L, "local-1", win())).isEqualTo(rejected("WRONG_USER"))
        verifyBlocking(store, never()) { transition(any(), any(), any()) }
        verifyBlocking(repository) { insertRejection(eq(99L), eq("local-1"), eq("WRONG_USER"), any()) }
    }

    @Test
    fun `발급 뒤 흐른 시간보다 긴 경기는 DURATION_EXCEEDS_WALL_CLOCK 이다`() = runBlocking<Unit> {
        givenSession(session())

        // 10 minutes passed since issue.
        val result = service().report(userId, "local-1", win(durationMillis = 700_000, frameCount = 42_000))

        assertThat(result).isEqualTo(rejected("DURATION_EXCEEDS_WALL_CLOCK"))
        verifyBlocking(repository) { insertRejection(eq(userId), eq("local-1"), eq("DURATION_EXCEEDS_WALL_CLOCK"), any()) }
        verifyBlocking(repository, never()) { recordAccepted(any()) }
    }

    @Test
    fun `frame 시간이 보고 시간보다 10퍼센트 넘게 길면 FRAME_DURATION_MISMATCH 다`() = runBlocking<Unit> {
        givenSession(session())

        assertThat(service().report(userId, "local-1", win(frameCount = 11_000))).isEqualTo(rejected("FRAME_DURATION_MISMATCH"))
    }

    @Test
    fun `느린 PC 라서 frame 이 모자란 결과는 받아들인다`() = runBlocking<Unit> {
        givenSession(session())

        // 154 s at a 60 tick target is 9_240 frames; 7_000 is a loop that averaged about 45 FPS.
        assertThat(service().report(userId, "local-1", win(frameCount = 7_000))).isEqualTo(accepted)
    }

    @Test
    fun `30초보다 짧은 승리는 TOO_SHORT 다`() = runBlocking<Unit> {
        givenSession(session())

        assertThat(service().report(userId, "local-1", win(durationMillis = 20_000, frameCount = 1_200)))
            .isEqualTo(rejected("TOO_SHORT"))
    }

    @Test
    fun `세션 참가자가 아닌 승자는 PARTICIPANT_MISMATCH 다`() = runBlocking<Unit> {
        givenSession(session())

        assertThat(service().report(userId, "local-1", win(lossUserId = -999))).isEqualTo(rejected("PARTICIPANT_MISMATCH"))
    }

    @Test
    fun `하루 상한을 넘으면 DAILY_LIMIT 이고 늘린 수를 되돌린다`() = runBlocking<Unit> {
        givenSession(session())
        whenever(store.incrementDailyAccepted(eq(userId), any())).thenReturn(101L)

        assertThat(service().report(userId, "local-1", win())).isEqualTo(rejected("DAILY_LIMIT"))
        verifyBlocking(store) { decrementDailyAccepted(userId, LocalDate.parse("2026-10-12")) }
        verifyBlocking(repository, never()) { recordAccepted(any()) }
    }

    @Test
    fun `접대봇을 이긴 연습 경기는 승수와 초보자 진행도를 함께 기록한다`() = runBlocking<Unit> {
        givenSession(session(hospitalityBot = true))

        assertThat(service().report(userId, "local-1", win())).isEqualTo(accepted)

        val written = argumentCaptor<AcceptedLocalResult>()
        verifyBlocking(repository) { recordAccepted(written.capture()) }
        assertThat(written.firstValue).isEqualTo(
            AcceptedLocalResult(
                userId = userId,
                gameType = "Practice",
                outcome = "WIN",
                winUserId = userId,
                lossUserId = ordinaryBotId,
                durationSeconds = 154,
                serverVersion = "1.3.0",
                decks = listOf(StatisticCount(userId, 1, 3)),
                magics = listOf(StatisticCount(userId, 1, 4)),
                finishScenarioId = null,
                incrementWins = true,
                noviceProgressStep = 0.15,
            ),
        )
    }

    @Test
    fun `일반 봇을 이기면 초보자 진행도는 그대로다`() = runBlocking<Unit> {
        givenSession(session(hospitalityBot = false))

        service().report(userId, "local-1", win())

        val written = argumentCaptor<AcceptedLocalResult>()
        verifyBlocking(repository) { recordAccepted(written.capture()) }
        assertThat(written.firstValue.noviceProgressStep).isNull()
        assertThat(written.firstValue.incrementWins).isTrue()
    }

    @Test
    fun `PVE 승리는 시나리오를 끝내고 패배는 아무것도 올리지 않는다`() = runBlocking<Unit> {
        givenSession(session(type = LocalSessionType.PVE))
        service().report(userId, "local-1", win(lossUserId = -1))

        givenSession(session(type = LocalSessionType.PVE))
        service().report(userId, "local-1", win(winUserId = -1, lossUserId = userId))

        val written = argumentCaptor<AcceptedLocalResult>()
        verifyBlocking(repository, org.mockito.kotlin.times(2)) { recordAccepted(written.capture()) }
        assertThat(written.firstValue.finishScenarioId).isEqualTo(3L)
        assertThat(written.firstValue.gameType).isEqualTo("PVE")
        assertThat(written.secondValue.finishScenarioId).isNull()
        assertThat(written.secondValue.incrementWins).isFalse()
    }

    @Test
    fun `기록이 실패하면 세션과 하루 수를 되돌리고 예외를 올린다`() = runBlocking<Unit> {
        givenSession(session())
        whenever(repository.recordAccepted(any())).thenThrow(IllegalStateException("database down"))

        assertThatThrownBy { runBlocking { service().report(userId, "local-1", win()) } }
            .isInstanceOf(IllegalStateException::class.java)
        verifyBlocking(store) { transition("local-1", LocalSessionState.REPORTED, LocalSessionState.ISSUED) }
        verifyBlocking(store) { decrementDailyAccepted(userId, LocalDate.parse("2026-10-12")) }
    }

    @Test
    fun `형식이 틀린 보고는 세션을 건드리지 않고 InvalidRequest 다`() = runBlocking<Unit> {
        givenSession(session())

        val result = service().report(userId, "local-1", win().copy(outcome = "LOSE"))

        assertThat(result).isInstanceOf(LocalResultReportResult.InvalidRequest::class.java)
        verifyBlocking(store, never()) { transition(any(), any(), any()) }
    }
}
