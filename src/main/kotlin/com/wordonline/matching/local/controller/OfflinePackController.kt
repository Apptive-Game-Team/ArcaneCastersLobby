package com.wordonline.matching.local.controller

import com.wordonline.matching.local.dto.OfflinePackResponse
import com.wordonline.matching.local.service.OfflinePackService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Static game data and the local server bundle for the desktop client. No login: the response holds
 * no real player's data, only bot rows (see [com.wordonline.matching.local.domain.StaticDataTables]).
 */
@RestController
class OfflinePackController(
    private val offlinePackService: OfflinePackService,
) {
    @GetMapping("/api/offline-pack")
    suspend fun getOfflinePack(@RequestParam(required = false) staticVersion: String?): OfflinePackResponse =
        offlinePackService.getPack(staticVersion)
}
