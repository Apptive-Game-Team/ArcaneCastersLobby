package com.wordonline.matching.appearance.controller

import com.wordonline.matching.appearance.dto.AppearanceResponseDto
import com.wordonline.matching.appearance.dto.AppearanceSelectRequestDto
import com.wordonline.matching.appearance.dto.AppearanceSelectResponseDto
import com.wordonline.matching.appearance.service.AppearanceSelection
import com.wordonline.matching.appearance.service.AppearanceService
import com.wordonline.matching.auth.service.UserId
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The signed-in user's appearances. `GET /api/users/mine` keeps returning the selected appearance as
 * before. Error statuses are returned as [ResponseEntity] rather than thrown, because
 * `GlobalExceptionHandler` turns every unhandled exception into 500.
 */
@RestController
@RequestMapping("/api/users/mine")
class AppearanceController(
    private val appearanceService: AppearanceService,
) {
    @GetMapping("/appearances")
    suspend fun getMyAppearances(@UserId userId: Long?): List<AppearanceResponseDto> =
        appearanceService.findMyAppearances(userId!!)

    /** 200 with the selected key, 400 without a key, 404 for an unknown key, 403 for a key the user does not own. */
    @PutMapping("/appearance")
    suspend fun selectAppearance(
        @UserId userId: Long?,
        @RequestBody request: AppearanceSelectRequestDto,
    ): ResponseEntity<Any> {
        val key = request.appearance
        if (key.isNullOrBlank()) {
            return ResponseEntity.badRequest().body("appearance is required")
        }
        return when (val selection = appearanceService.selectAppearance(userId!!, key)) {
            is AppearanceSelection.Selected -> ResponseEntity.ok(AppearanceSelectResponseDto(selection.key))
            AppearanceSelection.UnknownKey ->
                ResponseEntity.status(HttpStatus.NOT_FOUND).body("appearance '$key' does not exist")
            AppearanceSelection.NotOwned ->
                ResponseEntity.status(HttpStatus.FORBIDDEN).body("appearance '$key' is not owned")
            AppearanceSelection.UserNotFound ->
                ResponseEntity.status(HttpStatus.NOT_FOUND).body("user $userId not found")
        }
    }
}
