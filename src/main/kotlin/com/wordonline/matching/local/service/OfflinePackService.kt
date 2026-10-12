package com.wordonline.matching.local.service

import com.wordonline.matching.local.config.LocalServerProperties
import com.wordonline.matching.local.config.LocalSessionProperties
import com.wordonline.matching.local.dto.LocalServerInfo
import com.wordonline.matching.local.dto.OfflinePackResponse
import org.springframework.stereotype.Service

/** Composes `GET /api/offline-pack` from the cached static data and the local hosting settings. */
@Service
class OfflinePackService(
    private val staticDataService: StaticDataService,
    private val localServerProperties: LocalServerProperties,
    private val localSessionProperties: LocalSessionProperties,
) {
    suspend fun getPack(clientStaticVersion: String?): OfflinePackResponse {
        val staticData = staticDataService.current()
        return OfflinePackResponse(
            schemaVersion = staticData.schemaVersion,
            staticVersion = staticData.staticVersion,
            staticData = staticData.takeUnless { it.staticVersion == clientStaticVersion },
            localSessionsEnabled = localSessionProperties.enabled,
            localServer = localServerInfo(),
        )
    }

    private fun localServerInfo(): LocalServerInfo? {
        val version = localServerProperties.version.trim()
        if (version.isEmpty()) return null
        return LocalServerInfo(
            version = version,
            minimumVersion = localServerProperties.minimumVersion.trim().ifEmpty { version },
            assets = LocalServerProperties.PLATFORMS.associateWith { platform ->
                localServerProperties.assetUrlTemplate
                    .replace("{version}", version)
                    .replace("{platform}", platform)
            },
        )
    }
}
