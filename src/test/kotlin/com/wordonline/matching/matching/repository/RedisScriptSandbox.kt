package com.wordonline.matching.matching.repository

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JsePlatform

/**
 * Runs the repository's Lua scripts on the JVM against an in-memory key space.
 *
 * The two bugs this change fixes - a terminal TTL applied to `MATCHED` tickets, and an
 * active pointer that was never released - live entirely inside the scripts, where a
 * mocked `ReactiveStringRedisTemplate` proves nothing: it would happily record the call
 * and skip the branch under test. Executing the real script text is the only way to see
 * whether the `PEXPIRE` fires.
 *
 * Deliberately not a redis server (nor Testcontainers): the suite has to run on a machine
 * with no redis binary and no Docker. Only the commands the scripts actually use are
 * implemented, and anything else fails loudly rather than silently doing nothing.
 */
class RedisScriptSandbox(private val objectMapper: ObjectMapper = ObjectMapper()) {

    private val values = mutableMapOf<String, String>()
    private val expiries = mutableMapOf<String, Long>()
    private val sortedSets = mutableMapOf<String, MutableMap<String, Double>>()

    /** Channel to payload, in publish order. */
    val published = mutableListOf<Pair<String, String>>()

    fun set(key: String, value: String) {
        values[key] = value
        expiries.remove(key)
    }

    fun set(key: String, value: String, ttlMillis: Long) {
        values[key] = value
        expiries[key] = ttlMillis
    }

    fun get(key: String): String? = values[key]

    fun exists(key: String): Boolean = values.containsKey(key)

    /** Remaining TTL in milliseconds, or `null` when the key never expires. */
    fun ttlMillis(key: String): Long? = expiries[key]

    fun zadd(key: String, member: String, score: Double) {
        sortedSets.getOrPut(key) { linkedMapOf() }[member] = score
    }

    fun zmembers(key: String): Set<String> = sortedSets[key].orEmpty().keys.toSet()

    fun zrem(key: String, member: String) {
        sortedSets[key]?.remove(member)
    }

    fun zscore(key: String, member: String): Double? = sortedSets[key]?.get(member)

    fun eval(script: String, keys: List<String>, args: List<String>): Any? {
        val globals = JsePlatform.standardGlobals()
        globals.set("KEYS", luaArray(keys))
        globals.set("ARGV", luaArray(args))
        globals.set("redis", redisTable())
        globals.set("cjson", cjsonTable())
        return unwrap(globals.load(script, "script").call())
    }

    private fun luaArray(items: List<String>): LuaTable {
        val table = LuaTable()
        items.forEachIndexed { index, item -> table.set(index + 1, LuaValue.valueOf(item)) }
        return table
    }

    private fun unwrap(value: LuaValue): Any? = when {
        value.isnil() || value.isboolean() && !value.toboolean() -> null
        value.isnumber() -> value.tolong()
        else -> value.tojstring()
    }

    private fun redisTable(): LuaTable {
        val table = LuaTable()
        val call = object : VarArgFunction() {
            override fun invoke(varargs: Varargs): Varargs = dispatch(varargs)
        }
        table.set("call", call)
        table.set("pcall", call)
        return table
    }

    private fun dispatch(varargs: Varargs): LuaValue {
        val command = varargs.arg(1).tojstring().uppercase()
        fun arg(index: Int) = varargs.arg(index).tojstring()

        return when (command) {
            "GET" -> values[arg(2)]?.let { LuaValue.valueOf(it) } ?: LuaValue.FALSE
            "SET" -> {
                val key = arg(2)
                values[key] = arg(3)
                // Redis clears any existing TTL on a plain SET; only an explicit PX re-arms it.
                expiries.remove(key)
                if (varargs.narg() >= 5 && arg(4).equals("PX", ignoreCase = true)) {
                    expiries[key] = arg(5).toLong()
                }
                LuaValue.valueOf("OK")
            }
            "DEL" -> {
                var removed = 0
                for (index in 2..varargs.narg()) {
                    if (values.remove(arg(index)) != null) removed++
                    expiries.remove(arg(index))
                }
                LuaValue.valueOf(removed)
            }
            "PTTL" -> {
                val key = arg(2)
                when {
                    !values.containsKey(key) -> LuaValue.valueOf(-2)
                    else -> LuaValue.valueOf((expiries[key] ?: -1L).toDouble())
                }
            }
            "PEXPIRE" -> {
                val key = arg(2)
                if (!values.containsKey(key)) return LuaValue.valueOf(0)
                expiries[key] = arg(3).toLong()
                LuaValue.valueOf(1)
            }
            "ZADD" -> {
                val set = sortedSets.getOrPut(arg(2)) { linkedMapOf() }
                var index = 3
                while (index + 1 <= varargs.narg()) {
                    set[arg(index + 1)] = arg(index).toDouble()
                    index += 2
                }
                LuaValue.valueOf(1)
            }
            "ZREM" -> {
                val set = sortedSets[arg(2)] ?: return LuaValue.valueOf(0)
                var removed = 0
                for (index in 3..varargs.narg()) {
                    if (set.remove(arg(index)) != null) removed++
                }
                LuaValue.valueOf(removed)
            }
            "PUBLISH" -> {
                published += arg(2) to arg(3)
                LuaValue.valueOf(0)
            }
            else -> throw UnsupportedOperationException("redis command not emulated in tests: $command")
        }
    }

    private fun cjsonTable(): LuaTable {
        val table = LuaTable()
        table.set("decode", object : VarArgFunction() {
            override fun invoke(varargs: Varargs): Varargs = toLua(objectMapper.readTree(varargs.arg(1).tojstring()))
        })
        table.set("encode", object : VarArgFunction() {
            override fun invoke(varargs: Varargs): Varargs =
                LuaValue.valueOf(objectMapper.writeValueAsString(toJson(varargs.arg(1))))
        })
        return table
    }

    private fun toLua(node: JsonNode): LuaValue = when {
        node.isNull -> LuaValue.NIL
        node.isBoolean -> LuaValue.valueOf(node.booleanValue())
        node.isNumber -> LuaValue.valueOf(node.doubleValue())
        node.isTextual -> LuaValue.valueOf(node.textValue())
        node.isArray -> LuaTable().also { table ->
            node.forEachIndexed { index, child -> table.set(index + 1, toLua(child)) }
        }
        node.isObject -> LuaTable().also { table ->
            node.fields().forEach { (name, child) -> table.set(name, toLua(child)) }
        }
        else -> LuaValue.NIL
    }

    private fun toJson(value: LuaValue): JsonNode = when {
        value.isnil() -> objectMapper.nodeFactory.nullNode()
        value.isboolean() -> objectMapper.nodeFactory.booleanNode(value.toboolean())
        value.isnumber() -> numberNode(value.todouble())
        value.isstring() -> objectMapper.nodeFactory.textNode(value.tojstring())
        value.istable() -> tableToJson(value as LuaTable)
        else -> objectMapper.nodeFactory.nullNode()
    }

    private fun numberNode(value: Double): JsonNode =
        if (value == Math.floor(value) && !value.isInfinite()) {
            objectMapper.nodeFactory.numberNode(value.toLong())
        } else {
            objectMapper.nodeFactory.numberNode(value)
        }

    private fun tableToJson(table: LuaTable): JsonNode {
        val keys = generateSequence(LuaValue.NIL as LuaValue) { previous ->
            table.next(previous).arg1().takeIf { !it.isnil() }
        }.drop(1).toList()

        if (keys.isNotEmpty() && keys.all { it.isnumber() }) {
            val array: ArrayNode = objectMapper.nodeFactory.arrayNode()
            keys.sortedBy { it.todouble() }.forEach { array.add(toJson(table.get(it))) }
            return array
        }
        val obj: ObjectNode = objectMapper.nodeFactory.objectNode()
        keys.forEach { key -> obj.set<JsonNode>(key.tojstring(), toJson(table.get(key))) }
        return obj
    }
}

private inline fun JsonNode.forEachIndexed(action: (Int, JsonNode) -> Unit) {
    var index = 0
    for (child in this) {
        action(index, child)
        index++
    }
}
