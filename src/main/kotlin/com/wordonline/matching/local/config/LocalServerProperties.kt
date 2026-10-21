package com.wordonline.matching.local.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The local server bundle the desktop client downloads to host its own practice and adventure
 * matches, announced through `GET /api/offline-pack`.
 *
 * @property version latest bundle version. Empty means no bundle is published, and the offline
 *   pack sends `localServer: null`
 * @property minimumVersion oldest bundle the client may still run. Empty means the same as [version]
 * @property assetUrlTemplate download address of one platform's bundle; `{version}` and `{platform}`
 *   are replaced
 */
@ConfigurationProperties(prefix = "local-server")
data class LocalServerProperties(
    val version: String = "",
    val minimumVersion: String = "",
    val assetUrlTemplate: String =
        "https://raw.githubusercontent.com/Apptive-Game-Team/ArcaneCastersPlay/local-server-bundles/v{version}/local-server-{platform}.zip",
) {
    companion object {
        val PLATFORMS = listOf("windows-x64", "macos-x64", "macos-arm64", "linux-x64")
    }
}
