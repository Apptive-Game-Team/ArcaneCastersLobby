package com.wordonline.matching.chest.controller

import com.wordonline.matching.auth.service.UserId
import com.wordonline.matching.chest.dto.ChestOpenResponseDto
import com.wordonline.matching.chest.dto.UnopenedChestResponseDto
import com.wordonline.matching.chest.service.ChestOpenResult
import com.wordonline.matching.chest.service.ChestService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The signed-in user's treasure chests. Error statuses are returned as [ResponseEntity] rather than
 * thrown, because `GlobalExceptionHandler` turns every unhandled exception into 500.
 */
@RestController
@RequestMapping("/api/users/mine/chests")
class ChestController(
    private val chestService: ChestService,
) {
    /** Unopened chests only, oldest first. */
    @GetMapping
    suspend fun getMyChests(@UserId userId: Long?): List<UnopenedChestResponseDto> =
        chestService.findMyChests(userId!!)

    /** 200 with the granted rewards, 404 for a chest this user does not have, 409 for one already opened. */
    @PostMapping("/{id}/open")
    suspend fun openChest(@UserId userId: Long?, @PathVariable id: Long): ResponseEntity<Any> =
        when (val result = chestService.openChest(userId!!, id)) {
            is ChestOpenResult.Opened -> ResponseEntity.ok(ChestOpenResponseDto(result.rewards))
            ChestOpenResult.NotFound -> ResponseEntity.status(HttpStatus.NOT_FOUND).body("chest $id not found")
            ChestOpenResult.AlreadyOpened -> ResponseEntity.status(HttpStatus.CONFLICT).body("chest $id is already opened")
        }
}
