package com.lensdaemon.web.handlers

import android.content.Context
import com.lensdaemon.director.DirectorConfig
import com.lensdaemon.director.DirectorManager
import com.lensdaemon.director.DirectorState
import com.lensdaemon.director.InferenceMode
import com.lensdaemon.director.ParsedScript
import com.lensdaemon.director.TakeQuality
import com.lensdaemon.web.WebServer
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Handles all /api/director/ routes including script file management and take-recording linking.
 *
 * Extracted from ApiRoutes to separate director API concerns into a dedicated handler.
 * Refusals answer {"success": false, "error": reason}.
 */
class DirectorApiHandler(
    private val context: Context
) {

    var directorManager: DirectorManager? = null

    /**
     * Handle a director API request.
     *
     * @param uri The request URI
     * @param method The HTTP method
     * @param body The parsed JSON body (if any)
     * @return A NanoHTTPD.Response if the URI was handled, or null for unhandled URIs
     */
    fun handleRequest(uri: String, method: NanoHTTPD.Method, body: JSONObject?): NanoHTTPD.Response? {
        return when {
            // Director core routes
            uri == "/api/director/status" && method == NanoHTTPD.Method.GET -> getDirectorStatus()
            uri == "/api/director/enable" && method == NanoHTTPD.Method.POST -> enableDirector()
            uri == "/api/director/disable" && method == NanoHTTPD.Method.POST -> disableDirector()
            uri == "/api/director/config" && method == NanoHTTPD.Method.GET -> getDirectorConfig()
            uri == "/api/director/config" && method == NanoHTTPD.Method.PUT -> updateDirectorConfig(body)
            uri == "/api/director/script" && method == NanoHTTPD.Method.POST -> loadDirectorScript(body)
            uri == "/api/director/start" && method == NanoHTTPD.Method.POST -> startDirector()
            uri == "/api/director/stop" && method == NanoHTTPD.Method.POST -> stopDirector()
            uri == "/api/director/pause" && method == NanoHTTPD.Method.POST -> pauseDirector()
            uri == "/api/director/resume" && method == NanoHTTPD.Method.POST -> resumeDirector()
            uri == "/api/director/cue" && method == NanoHTTPD.Method.POST -> executeDirectorCue(body)
            uri == "/api/director/advance" && method == NanoHTTPD.Method.POST -> advanceDirectorCue()
            uri == "/api/director/scene" && method == NanoHTTPD.Method.POST -> jumpToScene(body)

            // Takes routes
            uri == "/api/director/takes" && method == NanoHTTPD.Method.GET -> getDirectorTakes()
            uri == "/api/director/takes/mark" && method == NanoHTTPD.Method.POST -> markTake(body)
            uri == "/api/director/takes/best" && method == NanoHTTPD.Method.GET -> getBestTakes()
            uri == "/api/director/takes/compare" && method == NanoHTTPD.Method.GET -> compareTakes()
            uri == "/api/director/takes/link" && method == NanoHTTPD.Method.POST -> linkTakeToRecording(body)
            uri == "/api/director/takes/markers" && method == NanoHTTPD.Method.GET -> getTakeMarkers()

            // Session and events
            uri == "/api/director/session" && method == NanoHTTPD.Method.GET -> getDirectorSession()
            uri == "/api/director/events" && method == NanoHTTPD.Method.GET -> getDirectorEvents()

            // Script clear
            uri == "/api/director/script/clear" && method == NanoHTTPD.Method.POST -> clearDirectorScript()

            // Script file management - specific routes BEFORE wildcard
            uri == "/api/director/scripts" && method == NanoHTTPD.Method.GET -> listScriptFiles()
            uri == "/api/director/scripts/save" && method == NanoHTTPD.Method.POST -> saveScriptFile(body)
            uri == "/api/director/scripts/export" && method == NanoHTTPD.Method.GET -> exportCurrentScript()
            uri == "/api/director/scripts/import" && method == NanoHTTPD.Method.POST -> importScript(body)

            // Script file wildcard routes (must be after specific /scripts/ routes)
            uri.startsWith("/api/director/scripts/") && method == NanoHTTPD.Method.GET -> loadScriptFile(uri)
            uri.startsWith("/api/director/scripts/") && method == NanoHTTPD.Method.DELETE -> deleteScriptFile(uri)

            else -> null
        }
    }

    // ---- Director Core ----

    private fun getDirectorStatus(): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        val status = director.getStatus()
        val json = JSONObject().apply {
            put("state", status.state.name)
            put("enabled", status.enabled)
            put("inferenceMode", status.inferenceMode.name)
            put("hasScript", status.hasScript)
            put("currentScene", status.currentScene ?: "")
            put("currentCue", status.currentCue ?: "")
            put("nextCue", status.nextCue ?: "")
            put("takeNumber", status.takeNumber)
            put("totalCues", status.totalCues)
            put("executedCues", status.executedCues)
            put("thermalProtectionActive", status.thermalProtectionActive)
        }
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    private fun enableDirector(): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        director.enable()
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON,
            """{"success": true, "message": "AI Director enabled", "state": "${director.state.value.name}"}""")
    }

    private fun disableDirector(): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        director.disable()
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON,
            """{"success": true, "message": "AI Director disabled (inert)", "state": "DISABLED"}""")
    }

    /** The whole configuration, without the LLM settings (and their key). */
    private fun getDirectorConfig(): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        val json = configJson(director.getConfig())
            .put("enabled", director.isEnabled())
            .put("thermalProtectionActive", director.getStatus().thermalProtectionActive)
        return reply(Status.OK, json)
    }

    /** A partial update: settings missing from the body keep their values, llmConfig included. */
    private fun updateDirectorConfig(body: JSONObject?): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        body ?: return ApiHandlerUtils.bodyRequired()
        val current = director.getConfig()
        val modeName = body.optString("inferenceMode", current.inferenceMode.name).uppercase()
        val mode = InferenceMode.entries.find { it.name == modeName }
            ?: return failure(Status.BAD_REQUEST, "Unknown inferenceMode $modeName: use PRE_PARSED or OFF")
        if (mode == InferenceMode.REMOTE) {
            return failure(Status.BAD_REQUEST, "REMOTE inference is not available yet; use PRE_PARSED or OFF")
        }
        director.updateConfig(updatedConfig(current, body, director.isEnabled()).copy(inferenceMode = mode))
        return reply(Status.OK, JSONObject().put("success", true).put("message", "Director configuration updated"))
    }

    private fun loadDirectorScript(body: JSONObject?): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        body ?: return ApiHandlerUtils.bodyRequired()
        val scriptText = body.optString("script", "")
        if (scriptText.isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, WebServer.MIME_JSON, """{"error": "script field is required"}""")
        }
        val result = director.loadScript(scriptText)
        val script = result.getOrNull() ?: return scriptRefused(director, result)
        return reply(Status.OK, scriptJson(script).put("success", true).put("message", "Script loaded successfully"))
    }

    /** Why a script was not loaded: 409 while the director runs or is paused, else 400. */
    private fun scriptRefused(director: DirectorManager, result: Result<ParsedScript>): NanoHTTPD.Response {
        val state = director.state.value
        val busy = state == DirectorState.RUNNING || state == DirectorState.PAUSED
        val reason = result.exceptionOrNull()?.message ?: "Failed to parse script"
        Timber.tag(TAG).w("Script not loaded: $reason")
        return failure(if (busy) Status.CONFLICT else Status.BAD_REQUEST, reason)
    }

    private fun startDirector(): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        if (director.startExecution()) {
            return reply(Status.OK, JSONObject().put("success", true).put("message", "Execution started"))
        }
        return startRefused(director, resuming = false)
    }

    private fun stopDirector(): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        director.stopExecution()
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON,
            """{"success": true, "message": "Execution stopped", "state": "${director.state.value.name}"}""")
    }

    private fun pauseDirector(): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        director.pauseExecution()
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON,
            """{"success": true, "message": "Execution paused", "state": "${director.state.value.name}"}""")
    }

    private fun resumeDirector(): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        if (director.resumeExecution()) {
            return reply(Status.OK, JSONObject().put("success", true).put("message", "Execution resumed"))
        }
        return startRefused(director, resuming = true)
    }

    /** Why the director did not start or resume: 409 while too hot (with the temperature) or running, else 400. */
    private fun startRefused(director: DirectorManager, resuming: Boolean): NanoHTTPD.Response {
        val state = director.state.value
        return when {
            state == DirectorState.THERMAL_HOLD -> {
                val temperature = director.lastTemperature()
                val reading = temperature?.let { " ($it°C)" } ?: ""
                val message = "Too hot to run the director$reading; it can start again below ${director.thermalResumeTemperature()}°C"
                reply(Status.CONFLICT, JSONObject().put("success", false).put("error", message).put("temperature", temperature))
            }
            state == DirectorState.DISABLED -> failure(Status.BAD_REQUEST, "The director is off")
            director.currentSession.value == null -> failure(Status.BAD_REQUEST, "No script loaded")
            state == DirectorState.RUNNING -> failure(Status.CONFLICT, "The director is already running")
            resuming -> failure(Status.BAD_REQUEST, "Not paused")
            else -> failure(Status.BAD_REQUEST, "Cannot start in state ${state.name}")
        }
    }

    private fun executeDirectorCue(body: JSONObject?): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        body ?: return ApiHandlerUtils.bodyRequired()
        val cueText = body.optString("cue", "")
        if (cueText.isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, WebServer.MIME_JSON, """{"error": "cue field is required"}""")
        }
        val json = JSONObject().put("cue", cueText)
        if (director.executeCueText(cueText)) {
            return reply(Status.OK, json.put("success", true))
        }
        val reason = if (director.isEnabled()) "Cue not recognised or could not be run" else "The director is off"
        return reply(Status.BAD_REQUEST, json.put("success", false).put("error", reason))
    }

    /** The next cue; while a script runs, it runs at once. */
    private fun advanceDirectorCue(): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        val nextCue = director.nextCue()
            ?: return reply(Status.OK, JSONObject().put("success", false).put("message", "No more cues").put("error", "No more cues"))
        val cue = JSONObject().put("type", nextCue.type.name).put("rawText", nextCue.rawText).put("lineNumber", nextCue.lineNumber)
        return reply(Status.OK, JSONObject().put("success", true).put("cue", cue))
    }

    /** Goes to a scene, given as "sceneIndex" (or "index"); while a script runs, the scene starts at once. */
    private fun jumpToScene(body: JSONObject?): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        body ?: return ApiHandlerUtils.bodyRequired()
        val sceneIndex = body.optInt("sceneIndex", body.optInt("index", -1))
        if (sceneIndex < 0) {
            return failure(Status.BAD_REQUEST, "sceneIndex is required and must be >= 0")
        }
        if (director.goToScene(sceneIndex)) {
            return reply(Status.OK, JSONObject().put("success", true).put("sceneIndex", sceneIndex))
        }
        val reason = when {
            !director.isEnabled() -> "The director is off"
            director.currentSession.value == null -> "No script loaded"
            else -> "No scene $sceneIndex in the loaded script"
        }
        return reply(Status.BAD_REQUEST, JSONObject().put("success", false).put("sceneIndex", sceneIndex).put("error", reason))
    }

    // ---- Takes ----

    private fun getDirectorTakes(): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        val takeManager = director.getTakeManager()
        val takes = takeManager.recordedTakes.value
        val json = JSONObject().apply {
            put("count", takes.size)
            put("takes", JSONArray().apply {
                takes.forEach { take ->
                    put(JSONObject().apply {
                        put("takeNumber", take.takeNumber)
                        put("sceneId", take.sceneId)
                        put("sceneLabel", take.sceneLabel)
                        put("startTimeMs", take.startTimeMs)
                        put("endTimeMs", take.endTimeMs)
                        put("durationMs", take.durationMs)
                        put("durationFormatted", take.durationFormatted)
                        put("filePath", take.filePath ?: "")
                        put("qualityScore", take.qualityScore)
                        put("qualityFactors", JSONObject().apply {
                            put("focusLockPercent", take.qualityFactors.focusLockPercent)
                            put("exposureStability", take.qualityFactors.exposureStability)
                            put("motionStability", take.qualityFactors.motionStability)
                            put("audioLevelOk", take.qualityFactors.audioLevelOk)
                            put("cueTimingAccuracy", take.qualityFactors.cueTimingAccuracy)
                        })
                        put("cuesExecuted", take.cuesExecuted)
                        put("cuesFailed", take.cuesFailed)
                        put("manualMark", take.manualMark.name)
                        put("suggested", take.suggested)
                        put("notes", take.notes)
                    })
                }
            })
        }
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    /** Marks a take: {"takeNumber", "quality"} (or "mark" for the quality). */
    private fun markTake(body: JSONObject?): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        body ?: return ApiHandlerUtils.bodyRequired()
        val takeNumber = body.optInt("takeNumber", -1)
        val qualityStr = body.optString("quality", "").ifEmpty { body.optString("mark", "") }
        val notes = body.optString("notes", "")
        if (takeNumber < 0) {
            return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, WebServer.MIME_JSON, """{"error": "takeNumber is required"}""")
        }
        val quality = try { TakeQuality.valueOf(qualityStr.uppercase()) } catch (e: Exception) {
            return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, WebServer.MIME_JSON, """{"error": "Invalid quality. Use: UNMARKED, GOOD, BAD, CIRCLE, HOLD"}""")
        }
        val success = director.getTakeManager().markTake(takeNumber, quality, notes)
        return NanoHTTPD.newFixedLengthResponse(
            if (success) Status.OK else Status.NOT_FOUND, WebServer.MIME_JSON,
            """{"success": $success, "takeNumber": $takeNumber, "quality": "${quality.name}"}""")
    }

    private fun getBestTakes(): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        val takeManager = director.getTakeManager()
        val bestTakes = takeManager.getAllBestTakes()
        val json = JSONObject().apply {
            put("count", bestTakes.size)
            put("bestTakes", JSONObject().apply {
                bestTakes.forEach { (sceneId, take) ->
                    put(sceneId, JSONObject().apply {
                        put("takeNumber", take.takeNumber)
                        put("sceneLabel", take.sceneLabel)
                        put("qualityScore", take.qualityScore)
                        put("durationFormatted", take.durationFormatted)
                    })
                }
            })
        }
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    private fun compareTakes(): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        val session = director.currentSession.value
        if (session == null) {
            return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, WebServer.MIME_JSON, """{"error": "No active session"}""")
        }
        val currentScene = session.currentScene
        if (currentScene == null) {
            return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, WebServer.MIME_JSON, """{"error": "No current scene"}""")
        }
        val comparison = director.getTakeManager().compareTakes(currentScene.id)
        val json = JSONObject().apply {
            put("sceneId", currentScene.id)
            put("sceneLabel", currentScene.label)
            put("recommendation", comparison.recommendation)
            val bestTake = comparison.bestTake
            put("bestTake", if (bestTake != null) {
                JSONObject().apply {
                    put("takeNumber", bestTake.takeNumber)
                    put("qualityScore", bestTake.qualityScore)
                }
            } else null)
            put("rankings", JSONArray().apply {
                comparison.rankings.forEach { (take, rank) ->
                    put(JSONObject().apply {
                        put("rank", rank)
                        put("takeNumber", take.takeNumber)
                        put("qualityScore", take.qualityScore)
                    })
                }
            })
        }
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    private fun linkTakeToRecording(body: JSONObject?): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        body ?: return ApiHandlerUtils.bodyRequired()
        val takeNumber = body.optInt("takeNumber", -1)
        val filePath = body.optString("filePath", "")
        if (takeNumber < 0 || filePath.isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, WebServer.MIME_JSON, """{"error": "takeNumber and filePath are required"}""")
        }
        if (!director.getTakeManager().linkTakeToFile(takeNumber, filePath)) {
            return failure(Status.NOT_FOUND, "Take $takeNumber not found")
        }
        return reply(Status.OK, JSONObject().put("success", true).put("takeNumber", takeNumber).put("filePath", filePath))
    }

    private fun getTakeMarkers(): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        val takeManager = director.getTakeManager()
        val takes = takeManager.recordedTakes.value
        val markers = mutableListOf<JSONObject>()
        takes.forEach { take ->
            markers.add(JSONObject().apply {
                put("type", "TAKE_START")
                put("takeNumber", take.takeNumber)
                put("sceneId", take.sceneId)
                put("sceneLabel", take.sceneLabel)
                put("timestampMs", take.startTimeMs)
            })
            if (take.endTimeMs > 0) {
                markers.add(JSONObject().apply {
                    put("type", "TAKE_END")
                    put("takeNumber", take.takeNumber)
                    put("sceneId", take.sceneId)
                    put("timestampMs", take.endTimeMs)
                    put("qualityScore", take.qualityScore)
                    put("durationMs", take.durationMs)
                })
            }
        }
        val json = JSONObject().apply {
            put("success", true)
            put("count", markers.size)
            put("markers", JSONArray(markers))
        }
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    // ---- Session & Events ----

    private fun getDirectorSession(): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        val session = director.currentSession.value
        val takeManager = director.getTakeManager()
        val stats = takeManager.getSessionStats()
        val json = JSONObject().apply {
            put("hasSession", session != null)
            if (session != null) {
                put("script", JSONObject().apply {
                    put("scenes", session.script.scenes.size)
                    put("totalCues", session.script.totalCues)
                    put("estimatedDuration", session.script.estimatedDurationFormatted)
                })
                put("currentSceneIndex", session.currentSceneIndex)
                put("currentCueIndex", session.currentCueIndex)
                put("currentScene", session.currentScene?.label ?: "")
                put("state", director.state.value.name)
            }
            put("stats", JSONObject().apply {
                put("totalTakes", stats.totalTakes)
                put("uniqueScenes", stats.uniqueScenes)
                put("avgQualityScore", stats.avgQualityScore)
                put("bestTakeCount", stats.bestTakeCount)
                put("circledTakes", stats.circledTakes)
                put("totalDuration", stats.totalDurationFormatted)
                put("cueSuccessRate", stats.cueSuccessRate)
            })
        }
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    /** Server-Sent Events: unnamed data frames whose JSON has a type (see [DirectorEventStream]). */
    private fun getDirectorEvents(): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        return DirectorEventStream.response(director)
    }

    // ---- Script Management ----

    private fun clearDirectorScript(): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        director.clearScript()
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, """{"success": true, "message": "Script cleared"}""")
    }

    private fun listScriptFiles(): NanoHTTPD.Response {
        val scriptsDir = File(context.filesDir, "director_scripts")
        if (!scriptsDir.exists()) { scriptsDir.mkdirs() }
        val scripts = scriptsDir.listFiles()?.mapNotNull { file ->
            if (file.isFile && file.name.endsWith(".txt")) {
                JSONObject().apply {
                    put("name", file.nameWithoutExtension)
                    put("fileName", file.name)
                    put("sizeBytes", file.length())
                    put("lastModified", file.lastModified())
                }
            } else null
        } ?: emptyList()
        val json = JSONObject().apply {
            put("success", true)
            put("count", scripts.size)
            put("scripts", JSONArray(scripts))
        }
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    private fun saveScriptFile(body: JSONObject?): NanoHTTPD.Response {
        body ?: return ApiHandlerUtils.bodyRequired()
        val rawFileName = body.optString("fileName", "")
        val scriptText = body.optString("script", "")
        if (rawFileName.isEmpty() || scriptText.isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, WebServer.MIME_JSON, """{"error": "fileName and script are required"}""")
        }
        val baseName = ApiHandlerUtils.sanitizeFileName(rawFileName) ?: return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, WebServer.MIME_JSON, """{"error": "Invalid file name"}""")
        val scriptsDir = File(context.filesDir, "director_scripts")
        scriptsDir.mkdirs()
        val actualFileName = if (baseName.endsWith(".txt")) baseName else "$baseName.txt"
        val file = File(scriptsDir, actualFileName)
        if (!ApiHandlerUtils.validateFileInDirectory(file, scriptsDir)) {
            return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, WebServer.MIME_JSON, """{"error": "Invalid file name"}""")
        }
        return try {
            file.writeText(scriptText)
            NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, """{"success": true, "fileName": "$actualFileName", "message": "Script saved"}""")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to save script")
            NanoHTTPD.newFixedLengthResponse(Status.INTERNAL_ERROR, WebServer.MIME_JSON, """{"error": "Failed to save script"}""")
        }
    }

    /** Returns a saved script and loads it; "loaded" false with an "error" when the director refused it. */
    private fun loadScriptFile(uri: String): NanoHTTPD.Response {
        val rawFileName = uri.removePrefix("/api/director/scripts/")
        if (rawFileName.isEmpty() || rawFileName == "save" || rawFileName == "export" || rawFileName == "import") {
            return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, WebServer.MIME_JSON, """{"error": "Invalid file name"}""")
        }
        val fileName = ApiHandlerUtils.sanitizeFileName(rawFileName) ?: return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, WebServer.MIME_JSON, """{"error": "Invalid file name"}""")
        val scriptsDir = File(context.filesDir, "director_scripts")
        val file = File(scriptsDir, fileName)
        if (!ApiHandlerUtils.validateFileInDirectory(file, scriptsDir)) {
            return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, WebServer.MIME_JSON, """{"error": "Invalid file name"}""")
        }
        if (!file.exists()) {
            return NanoHTTPD.newFixedLengthResponse(Status.NOT_FOUND, WebServer.MIME_JSON, """{"error": "Script file not found"}""")
        }
        return try {
            val content = file.readText()
            val loadResult = directorManager?.loadScript(content)
            val script = loadResult?.getOrNull()
            val json = (script?.let(::scriptJson) ?: JSONObject())
                .put("success", true)
                .put("fileName", fileName)
                .put("content", content)
                .put("loaded", script != null)
            if (script == null) {
                json.put("error", loadResult?.exceptionOrNull()?.message ?: "Director service not available")
            }
            NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to read script")
            NanoHTTPD.newFixedLengthResponse(Status.INTERNAL_ERROR, WebServer.MIME_JSON, """{"error": "Failed to read script"}""")
        }
    }

    private fun deleteScriptFile(uri: String): NanoHTTPD.Response {
        val rawFileName = uri.removePrefix("/api/director/scripts/")
        val fileName = ApiHandlerUtils.sanitizeFileName(rawFileName) ?: return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, WebServer.MIME_JSON, """{"error": "Invalid file name"}""")
        val scriptsDir = File(context.filesDir, "director_scripts")
        val file = File(scriptsDir, fileName)
        if (!ApiHandlerUtils.validateFileInDirectory(file, scriptsDir)) {
            return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, WebServer.MIME_JSON, """{"error": "Invalid file name"}""")
        }
        if (!file.exists()) {
            return NanoHTTPD.newFixedLengthResponse(Status.NOT_FOUND, WebServer.MIME_JSON, """{"error": "Script file not found"}""")
        }
        return if (file.delete()) {
            NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, """{"success": true, "message": "Script deleted"}""")
        } else {
            NanoHTTPD.newFixedLengthResponse(Status.INTERNAL_ERROR, WebServer.MIME_JSON, """{"error": "Failed to delete script"}""")
        }
    }

    private fun exportCurrentScript(): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        val session = director.currentSession.value
        if (session == null) {
            return NanoHTTPD.newFixedLengthResponse(Status.NOT_FOUND, WebServer.MIME_JSON, """{"error": "No script currently loaded"}""")
        }
        val json = JSONObject().apply {
            put("success", true)
            put("script", session.script.rawScript)
            put("scenes", session.script.scenes.size)
            put("totalCues", session.script.totalCues)
        }
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    /** Loads a script and, when asked and it loaded, saves it as a script file. */
    private fun importScript(body: JSONObject?): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        body ?: return ApiHandlerUtils.bodyRequired()
        val scriptText = body.optString("script", "")
        val fileName = body.optString("fileName", "")
        val saveToFile = body.optBoolean("save", false)
        if (scriptText.isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, WebServer.MIME_JSON, """{"error": "script field is required"}""")
        }
        val result = director.loadScript(scriptText)
        val script = result.getOrNull() ?: return scriptRefused(director, result)
        var saved = false
        if (saveToFile && fileName.isNotEmpty()) {
            val safeName = ApiHandlerUtils.sanitizeFileName(fileName)
            if (safeName != null) {
                val scriptsDir = File(context.filesDir, "director_scripts")
                scriptsDir.mkdirs()
                val actualFileName = if (safeName.endsWith(".txt")) safeName else "$safeName.txt"
                val file = File(scriptsDir, actualFileName)
                if (ApiHandlerUtils.validateFileInDirectory(file, scriptsDir)) {
                    file.writeText(scriptText)
                    saved = true
                }
            }
        }
        return reply(Status.OK, scriptJson(script).put("success", true).put("message", "Script imported").put("saved", saved))
    }

    companion object {
        private const val TAG = "DirectorApiHandler"
    }
}

private fun reply(status: Status, json: JSONObject): NanoHTTPD.Response =
    ApiHandlerUtils.jsonResponse(status, json.toString())

/** A refusal: {"success": false, "error": [message]}. */
private fun failure(status: Status, message: String): NanoHTTPD.Response =
    reply(status, JSONObject().put("success", false).put("error", message))

/** A loaded script's summary, with the problems found in it. */
private fun scriptJson(script: ParsedScript): JSONObject = JSONObject()
    .put("scenes", script.scenes.size)
    .put("totalCues", script.totalCues)
    .put("estimatedDuration", script.estimatedDurationFormatted)
    .put("errors", JSONArray(script.errors))

/** The director settings GET /api/director/config shows: all but the LLM settings, whose key stays private. */
private fun configJson(config: DirectorConfig): JSONObject = JSONObject()
    .put("inferenceMode", config.inferenceMode.name)
    .put("autoTakeSeparation", config.autoTakeSeparation)
    .put("qualityScoring", config.qualityScoring)
    .put("thermalAutoDisable", config.thermalAutoDisable)
    .put("thermalThresholdInference", config.thermalThresholdInference)
    .put("thermalThresholdDisable", config.thermalThresholdDisable)
    .put("defaultTransitionDurationMs", config.defaultTransitionDurationMs)
    .put("defaultHoldDurationMs", config.defaultHoldDurationMs)

/** [current] with the settings [body] carries; the others (llmConfig among them) keep their values. */
private fun updatedConfig(current: DirectorConfig, body: JSONObject, enabled: Boolean): DirectorConfig = current.copy(
    enabled = body.optBoolean("enabled", enabled),
    autoTakeSeparation = body.optBoolean("autoTakeSeparation", current.autoTakeSeparation),
    qualityScoring = body.optBoolean("qualityScoring", current.qualityScoring),
    thermalAutoDisable = body.optBoolean("thermalAutoDisable", current.thermalAutoDisable),
    thermalThresholdInference = body.optInt("thermalThresholdInference", current.thermalThresholdInference),
    thermalThresholdDisable = body.optInt("thermalThresholdDisable", current.thermalThresholdDisable),
    defaultTransitionDurationMs = body.optLong("defaultTransitionDurationMs", current.defaultTransitionDurationMs),
    defaultHoldDurationMs = body.optLong("defaultHoldDurationMs", current.defaultHoldDurationMs)
)
