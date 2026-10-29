package com.wordonline.matching.progression

import com.wordonline.matching.auth.service.UserId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

data class ChooseFactionRequest(val faction: String)

@RestController
@RequestMapping("/api/users/mine/progression")
class ProgressionController(private val service: ProgressionService) {
    @GetMapping("/rewards")
    suspend fun rewards(@UserId userId: Long?): ResponseEntity<ProgressionRewardsResponse> =
        service.rewards(userId!!)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.status(409).build()

    @PostMapping("/sync")
    suspend fun sync(@UserId userId: Long?): ProgressionResponse = service.sync(userId!!)

    @PostMapping("/faction")
    suspend fun choose(@UserId userId: Long?, @RequestBody request: ChooseFactionRequest): ResponseEntity<Any> {
        if (request.faction !in setOf("HELL", "NATURE")) return ResponseEntity.badRequest().body("Unknown faction")
        val result = service.choose(userId!!, request.faction)
            ?: return ResponseEntity.status(409).body("Faction already selected")
        return ResponseEntity.ok(result)
    }
}
