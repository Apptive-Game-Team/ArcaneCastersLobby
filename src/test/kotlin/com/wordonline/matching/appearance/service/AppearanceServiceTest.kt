package com.wordonline.matching.appearance.service

import com.wordonline.matching.appearance.repository.AppearanceKeyOwnership
import com.wordonline.matching.appearance.repository.AppearanceRepository
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking

class AppearanceServiceTest {

    private val userId = 1L

    @Test
    @DisplayName("가진_외형을_고르면_한_문장의_UPDATE_로_끝나고_다시_읽지_않는다")
    fun selectOwnedAppearance() = runTest {
        val repository = mock<AppearanceRepository> { onBlocking { selectIfOwned(userId, "storm") } doReturn 1L }

        assertThat(AppearanceService(repository).selectAppearance(userId, "storm"))
            .isEqualTo(AppearanceSelection.Selected("storm"))
        verifyBlocking(repository, never()) { findKeyOwnership(any(), any()) }
    }

    @Test
    @DisplayName("UPDATE_가_0_행이면_없는_key_는_UnknownKey_가지지_않은_key_는_NotOwned_가진_key_는_UserNotFound_다")
    fun zeroRowsDecidesWhy() = runTest {
        val repository = mock<AppearanceRepository> {
            onBlocking { selectIfOwned(any(), any()) } doReturn 0L
            onBlocking { findKeyOwnership(userId, "nope") } doReturn null
            onBlocking { findKeyOwnership(userId, "golem") } doReturn AppearanceKeyOwnership(owned = false)
            onBlocking { findKeyOwnership(userId, "default") } doReturn AppearanceKeyOwnership(owned = true)
        }
        val service = AppearanceService(repository)

        assertThat(service.selectAppearance(userId, "nope")).isEqualTo(AppearanceSelection.UnknownKey)
        assertThat(service.selectAppearance(userId, "golem")).isEqualTo(AppearanceSelection.NotOwned)
        assertThat(service.selectAppearance(userId, "default")).isEqualTo(AppearanceSelection.UserNotFound)
    }
}
