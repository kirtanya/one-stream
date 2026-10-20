package com.kirtanya.reel.core.round

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.system.exitProcess

/**
 * Command line for `tool/device_round.sh` (run on the Mac with the :core classpath):
 *
 *   gate <dumps file>              prints `idle` (exit 0) or `busy: <why>` (exit 3)
 *   report <round dir> <latest>    reads each `<round dir>/<device>/` (meta.json from the script,
 *                                  reelstats.txt, crash.txt), prints the before / now table per device,
 *                                  writes `<round dir>/summary.json` and updates `<latest>` for devices
 *                                  that passed. Exit 1 when a device failed (crash, ANR, no playback).
 *
 * The dumps file has sections `### media_session`, `### top`, `### window`, `### power`, `### home`.
 */
object DeviceRoundCli {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    @JvmStatic
    fun main(args: Array<String>) {
        val code = when (args.firstOrNull()) {
            "gate" -> gate(File(args[1]))
            "report" -> report(File(args[1]), File(args[2]))
            else -> { System.err.println("usage: gate <dumps> | report <round dir> <latest.json>"); 2 }
        }
        exitProcess(code)
    }

    fun sections(text: String): Map<String, String> {
        val out = LinkedHashMap<String, StringBuilder>()
        var current: StringBuilder? = null
        for (line in text.lines()) {
            if (line.startsWith("### ")) current = StringBuilder().also { out[line.removePrefix("### ").trim()] = it }
            else current?.appendLine(line)
        }
        return out.mapValues { it.value.toString() }
    }

    private fun gate(file: File): Int {
        val s = sections(file.readText())
        val home = s["home"]?.lines()?.map { it.trim() }?.lastOrNull { '/' in it }?.substringBefore('/')
        return when (val r = IdleGate.check(IdleGate.Dumps(s["media_session"].orEmpty(), s["top"].orEmpty(), s["window"].orEmpty(), s["power"].orEmpty(), home))) {
            IdleGate.Result.Idle -> { println("idle"); 0 }
            is IdleGate.Result.Busy -> { println("busy: ${r.reason}"); 3 }
        }
    }

    /** One device's part of summary.json. */
    fun deviceSummary(meta: JsonObject, metrics: RoundMetrics?, rows: List<RoundRow>, failures: List<String>): JsonObject = buildJsonObject {
        meta.forEach { (k, v) -> put(k, v) }
        if (metrics != null) put("metrics", json.encodeToJsonElement(RoundMetrics.serializer(), metrics))
        put("table", JsonArray(rows.map { json.encodeToJsonElement(RoundRow.serializer(), it) }))
        put("failures", JsonArray(failures.map(::JsonPrimitive)))
    }

    private fun report(roundDir: File, latestFile: File): Int {
        val latest = latestFile.takeIf { it.isFile }?.let { json.parseToJsonElement(it.readText()).jsonObject }?.toMutableMap() ?: mutableMapOf()
        val devices = LinkedHashMap<String, JsonElement>()
        var failed = false
        val dirs = roundDir.listFiles { f -> File(f, "meta.json").isFile }.orEmpty().sortedBy { it.name }
        for (dir in dirs) {
            val meta = json.parseToJsonElement(File(dir, "meta.json").readText()).jsonObject
            val device = meta["device"]?.jsonPrimitive?.contentOrNull ?: dir.name
            val status = meta["status"]?.jsonPrimitive?.contentOrNull ?: "?"
            println()
            if (status == "skipped") {
                println("$device: skipped — ${meta["reason"]?.jsonPrimitive?.contentOrNull ?: "?"}")
                devices[device] = deviceSummary(meta, null, emptyList(), emptyList())
                continue
            }
            val stats = File(dir, "reelstats.txt").takeIf { it.isFile }?.readLines().orEmpty()
            val crash = File(dir, "crash.txt").takeIf { it.isFile }?.readLines().orEmpty()
            val metrics = RoundLog.parse(stats, crash, meta["sampleKey"]?.jsonPrimitive?.contentOrNull)
            val before = (latest[device] as? JsonObject)?.get("metrics")?.let { json.decodeFromJsonElement(RoundMetrics.serializer(), it) }
            val beforeRound = (latest[device] as? JsonObject)?.get("round")?.jsonPrimitive?.contentOrNull
            val beforeVersion = (latest[device] as? JsonObject)?.get("version")?.jsonPrimitive?.contentOrNull
            val rows = RoundCompare.rows(before, metrics)
            // A round the user interrupted (they picked the device up) is not a failure of the app.
            val failures = if (status == "aborted" && metrics.crashes == 0 && metrics.anrs == 0) emptyList() else RoundCompare.failures(metrics)
            val version = meta["version"]?.jsonPrimitive?.contentOrNull ?: "?"
            println("$device ($version" + (beforeRound?.let { ", before = round $it, $beforeVersion" } ?: ", no earlier round") + ")")
            if (status == "aborted") println("  aborted — ${meta["reason"]?.jsonPrimitive?.contentOrNull ?: "?"}")
            println(RoundCompare.table(rows))
            if (failures.isNotEmpty()) { failed = true; println("  FAILED: " + failures.joinToString("; ")) }
            devices[device] = deviceSummary(meta, metrics, rows, failures)
            // Only a clean, complete round becomes the next comparison point.
            if (status == "ran" && failures.isEmpty()) {
                latest[device] = buildJsonObject {
                    put("round", roundDir.name)
                    put("version", version)
                    put("metrics", json.encodeToJsonElement(RoundMetrics.serializer(), metrics))
                }
            }
        }
        if (dirs.isEmpty()) println("No device directories in $roundDir")
        val summary = buildJsonObject {
            put("round", roundDir.name)
            put("failed", failed)
            put("devices", JsonObject(devices))
        }
        File(roundDir, "summary.json").writeText(json.encodeToString(JsonElement.serializer(), summary) + "\n")
        latestFile.writeText(json.encodeToString(JsonElement.serializer(), JsonObject(latest)) + "\n")
        return if (failed) 1 else 0
    }
}
