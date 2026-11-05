package com.wordonline.matching.progression

import com.wordonline.matching.auth.service.UserId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

data class ChooseFactionRequest(val faction: String)

@RestController
@RequestMapping("/api/users/mine/progression")
class ProgressionController(private val service: ProgressionService, private val claimPolicy: RewardClaimPolicy = RewardClaimPolicy()) {
    @GetMapping("/rewards")
    suspend fun rewards(@UserId userId: Long?): ResponseEntity<ProgressionRewardsResponse> =
        service.rewards(userId!!)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.status(409).build()

    @PostMapping("/sync")
    suspend fun sync(@UserId userId: Long?, @RequestHeader("X-Reward-Claims", required = false) capability: String? = null): ProgressionResponse =
        service.sync(userId!!, claimPolicy.manual(capability))

    @GetMapping("/rewards/pending")
    suspend fun pending(@UserId userId: Long?, @RequestHeader("X-Reward-Claims", required = false) capability: String? = null): PendingProgressionRewardsResponse =
        service.pendingRewards(userId!!, claimPolicy.manual(capability))

    @PostMapping("/rewards/{id}/claim")
    suspend fun claim(@UserId userId: Long?, @PathVariable id: Long,
        @RequestHeader("X-Reward-Claims", required = false) capability: String? = null): ResponseEntity<ProgressionClaimResponse> =
        service.claimReward(userId!!, id, claimPolicy.manual(capability))?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @PostMapping("/faction")
    suspend fun choose(@UserId userId: Long?, @RequestBody request: ChooseFactionRequest,
        @RequestHeader("X-Reward-Claims", required = false) capability: String? = null): ResponseEntity<Any> {
        if (request.faction !in setOf("HELL", "NATURE")) return ResponseEntity.badRequest().body("Unknown faction")
        val result = service.choose(userId!!, request.faction, claimPolicy.manual(capability))
            ?: return ResponseEntity.status(409).body("Faction already selected")
        return ResponseEntity.ok(result)
    }
}
