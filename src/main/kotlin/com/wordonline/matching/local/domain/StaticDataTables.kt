package com.wordonline.matching.local.domain

/**
 * One table of the offline pack's static data and the rows of it that may leave the lobby.
 *
 * @property columns the only columns sent, in this order; `null` sends every column
 * @property where SQL condition on the rows sent; `null` sends every row
 */
data class StaticTableSpec(
    val name: String,
    val columns: List<String>? = null,
    val where: String? = null,
)

/**
 * The tables the local server loads, from the local hosting contract (section 1).
 *
 * Real players (`id >= 0`) must never leave the lobby through this list: `users` sends only the
 * bot rows and a handful of their columns, and `decks` and `deck_cards` only what the bots own.
 */
object StaticDataTables {

    const val SCHEMA_VERSION = 1

    val SPECS: List<StaticTableSpec> = listOf(
        "parameters",
        "parameter_values",
        "magic_parameters",
        "game_objects",
        "prefab_elements",
        "magics",
        "magic_game_object_aliases",
        "tags",
        "tag_counter_rules",
        "game_object_tags",
        "magic_tags",
        "adventures",
        "stages",
        "scenarios",
        "pve_scenario_objectives",
        "pve_scenario_installers",
        "pve_scenario_events",
        "pve_scenario_event_lines",
        "pve_scenario_event_actions",
        "pve_scenario_rules",
        "pve_scenario_shields",
        "bot_personas",
    ).map { StaticTableSpec(it) } + listOf(
        StaticTableSpec(
            "users",
            columns = listOf("id", "selected_deck_id", "mmr", "status", "total_wins", "novice_progress"),
            where = "id < 0",
        ),
        StaticTableSpec("decks", where = "user_id < 0"),
        StaticTableSpec("deck_cards", where = "deck_id IN (SELECT id FROM decks WHERE user_id < 0)"),
    )
}
