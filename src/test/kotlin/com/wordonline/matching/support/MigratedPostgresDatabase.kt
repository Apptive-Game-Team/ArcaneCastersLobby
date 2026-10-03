package com.wordonline.matching.support

import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryOptions
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.io.File

/**
 * Builds a scratch Postgres database from the real WordOnlineDatabase migrations, for the
 * integration tests gated on `QUEST_IT_DATABASE_URL`.
 *
 * It does not touch the database that URL names: it drops and recreates [databaseName] on the same
 * server (so the user needs `CREATEDB`) and replays every `V*.sql` file of `QUEST_IT_MIGRATION_DIR`
 * (default `../database/migration`) in version order, each file in one transaction like
 * `psql --single-transaction`.
 */
object MigratedPostgresDatabase {

    const val DATABASE_URL_VARIABLE = "QUEST_IT_DATABASE_URL"
    const val MIGRATION_DIRECTORY_VARIABLE = "QUEST_IT_MIGRATION_DIR"

    /**
     * Recreates [databaseName], replays the migrations, and returns a connection factory for it.
     * Fails when the migration directory holds no `V<requiredVersion>_` file, so a stale directory
     * is reported instead of failing later on a missing column.
     */
    suspend fun build(databaseName: String, requiredVersion: Int): ConnectionFactory {
        val baseOptions = ConnectionFactoryOptions.parse(System.getenv(DATABASE_URL_VARIABLE))
        val migrations = migrationFiles(requiredVersion)

        withConnection(ConnectionFactories.get(directOptions(baseOptions, baseOptions.getValue(ConnectionFactoryOptions.DATABASE) as String))) {
            executeSimple(it, "DROP DATABASE IF EXISTS $databaseName WITH (FORCE)")
            executeSimple(it, "CREATE DATABASE $databaseName")
        }
        // One unpooled connection replays everything. V001 sets search_path to '' for its session,
        // so this connection is closed afterwards instead of going back to a pool.
        withConnection(ConnectionFactories.get(directOptions(baseOptions, databaseName))) { connection ->
            migrations.forEach { file ->
                try {
                    executeSimple(connection, "BEGIN;\n${file.readText()}\n;COMMIT;")
                } catch (e: Exception) {
                    throw IllegalStateException("migration ${file.name} failed", e)
                }
            }
        }

        return ConnectionFactories.get(
            ConnectionFactoryOptions.builder().from(baseOptions).option(ConnectionFactoryOptions.DATABASE, databaseName).build(),
        )
    }

    private fun migrationFiles(requiredVersion: Int): List<File> {
        val directory = File(System.getenv(MIGRATION_DIRECTORY_VARIABLE) ?: "../database/migration")
        val files = directory.listFiles { file -> file.name.matches(Regex("""V\d+_.*\.sql""")) }
            ?.sortedBy(::versionOf)
            .orEmpty()
        check(files.any { versionOf(it) == requiredVersion }) {
            "$MIGRATION_DIRECTORY_VARIABLE (${directory.absolutePath}) must hold the WordOnlineDatabase migrations " +
                "through V${requiredVersion.toString().padStart(3, '0')}"
        }
        return files
    }

    private fun versionOf(file: File): Int = file.name.substringAfter('V').substringBefore('_').toInt()

    private fun directOptions(base: ConnectionFactoryOptions, database: String): ConnectionFactoryOptions =
        ConnectionFactoryOptions.builder()
            .option(ConnectionFactoryOptions.DRIVER, "postgresql")
            .option(ConnectionFactoryOptions.HOST, base.getRequiredValue(ConnectionFactoryOptions.HOST) as String)
            .option(ConnectionFactoryOptions.PORT, (base.getValue(ConnectionFactoryOptions.PORT) as Int?) ?: 5432)
            .option(ConnectionFactoryOptions.USER, base.getRequiredValue(ConnectionFactoryOptions.USER) as String)
            .option(ConnectionFactoryOptions.PASSWORD, base.getRequiredValue(ConnectionFactoryOptions.PASSWORD) as CharSequence)
            .option(ConnectionFactoryOptions.DATABASE, database)
            .build()

    private suspend fun withConnection(factory: ConnectionFactory, block: suspend (Connection) -> Unit) {
        val connection = Mono.from(factory.create()).awaitSingle()
        try {
            block(connection)
        } finally {
            Mono.from(connection.close()).awaitSingleOrNull()
        }
    }

    /** Runs [sql] without parameters, which the driver sends as one simple query, so it may hold many statements. */
    private suspend fun executeSimple(connection: Connection, sql: String) {
        Flux.from(connection.createStatement(sql).execute())
            .concatMap { result -> Flux.from(result.rowsUpdated) }
            .then()
            .awaitSingleOrNull()
    }
}
