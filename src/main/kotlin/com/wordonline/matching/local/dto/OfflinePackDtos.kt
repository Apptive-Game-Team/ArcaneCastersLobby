package com.wordonline.matching.local.dto

import java.time.Instant

/** Response of `GET /api/offline-pack`. */
data class OfflinePackResponse(
    val schemaVersion: Int,
    val staticVersion: String,
    /** `null` when the caller already holds [staticVersion]. */
    val staticData: StaticData?,
    val localSessionsEnabled: Boolean,
    /** `null` while no local server bundle is published. */
    val localServer: LocalServerInfo?,
)

/** The static game data the local server loads into its own database (`static.json`). */
data class StaticData(
    val schemaVersion: Int,
    val staticVersion: String,
    val generatedAt: Instant,
    /** Keyed by table name, in name order. */
    val tables: Map<String, StaticTable>,
)

data class StaticTable(
    val columns: List<String>,
    val rows: List<List<Any?>>,
)

data class LocalServerInfo(
    val version: String,
    val minimumVersion: String,
    /** Download address per platform, such as `windows-x64`. */
    val assets: Map<String, String>,
)
