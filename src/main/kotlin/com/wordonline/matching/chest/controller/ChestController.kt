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
import org.springframework.web.bind.annotation.RequestHeader
import com.wordonline.matching.progression.RewardClaimPolicy

/**
 * 인증된 사용자의 상자를 조회하고 개봉한다. 미소유 상자는 404, 이미 개봉한 상자는 409로 반환한다.
 */
@RestController
@RequestMapping("/api/users/mine/chests")
class ChestController(
    private val chestService: ChestService,
    private val claimPolicy: RewardClaimPolicy = RewardClaimPolicy(),
) {
    /** Unopened chests only, oldest first. */
    @GetMapping
    suspend fun getMyChests(@UserId userId: Long?): List<UnopenedChestResponseDto> =
        chestService.findMyChests(userId!!)

    /** 200 with the granted rewards, 404 for a chest this user does not have, 409 for one already opened. */
    @PostMapping("/{id}/open")
    suspend fun openChest(@UserId userId: Long?, @PathVariable id: Long,
        @RequestHeader("X-Reward-Claims", required = false) capability: String? = null): ResponseEntity<Any> =
        when (val result = chestService.openChest(userId!!, id, claimPolicy.manual(capability))) {
            is ChestOpenResult.Opened -> ResponseEntity.ok(ChestOpenResponseDto(result.rewards))
            ChestOpenResult.NotFound -> ResponseEntity.status(HttpStatus.NOT_FOUND).body("chest $id not found")
            ChestOpenResult.AlreadyOpened -> ResponseEntity.status(HttpStatus.CONFLICT).body("chest $id is already opened")
        }
}
