package com.lensdaemon.web.handlers

import android.content.Context
import com.lensdaemon.director.DirectorConfig
import com.lensdaemon.director.DirectorManager
import com.lensdaemon.director.InferenceMode
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

    private fun getDirectorConfig(): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        val status = director.getStatus()
        val json = JSONObject().apply {
            put("enabled", status.enabled)
            put("inferenceMode", status.inferenceMode.name)
            put("thermalProtectionActive", status.thermalProtectionActive)
        }
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    private fun updateDirectorConfig(body: JSONObject?): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        body ?: return ApiHandlerUtils.bodyRequired()
        val enabled = body.optBoolean("enabled", director.isEnabled())
        val inferenceModeStr = body.optString("inferenceMode", "PRE_PARSED")
        val inferenceMode = try { InferenceMode.valueOf(inferenceModeStr.uppercase()) } catch (e: Exception) { InferenceMode.PRE_PARSED }
        val config = DirectorConfig(
            enabled = enabled, inferenceMode = inferenceMode,
            autoTakeSeparation = body.optBoolean("autoTakeSeparation", true),
            qualityScoring = body.optBoolean("qualityScoring", true),
            thermalAutoDisable = body.optBoolean("thermalAutoDisable", true),
            thermalThresholdInference = body.optInt("thermalThresholdInference", 50),
            thermalThresholdDisable = body.optInt("thermalThresholdDisable", 55),
            defaultTransitionDurationMs = body.optLong("defaultTransitionDurationMs", 1000),
            defaultHoldDurationMs = body.optLong("defaultHoldDurationMs", 2000)
        )
        director.updateConfig(config)
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON,
            """{"success": true, "message": "Director configuration updated"}""")
    }

    private fun loadDirectorScript(body: JSONObject?): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        body ?: return ApiHandlerUtils.bodyRequired()
        val scriptText = body.optString("script", "")
        if (scriptText.isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, WebServer.MIME_JSON, """{"error": "script field is required"}""")
        }
        val result = director.loadScript(scriptText)
        return if (result.isSuccess) {
            val script = result.getOrNull()!!
            val json = JSONObject().apply {
                put("success", true)
                put("message", "Script loaded successfully")
                put("scenes", script.scenes.size)
                put("totalCues", script.totalCues)
                put("estimatedDuration", script.estimatedDurationFormatted)
                put("errors", JSONArray().apply { script.errors.forEach { put(it) } })
            }
            NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
        } else {
            Timber.tag(TAG).w("Script load failed: ${result.exceptionOrNull()?.message}")
            NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, WebServer.MIME_JSON, """{"success": false, "error": "Failed to parse script"}""")
        }
    }

    private fun startDirector(): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        val success = director.startExecution()
        return NanoHTTPD.newFixedLengthResponse(
            if (success) Status.OK else Status.BAD_REQUEST, WebServer.MIME_JSON,
            """{"success": $success, "message": "${if (success) "Execution started" else "Cannot start - check state and script"}"}""")
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
        val success = director.resumeExecution()
        return NanoHTTPD.newFixedLengthResponse(
            if (success) Status.OK else Status.BAD_REQUEST, WebServer.MIME_JSON,
            """{"success": $success, "message": "${if (success) "Execution resumed" else "Cannot resume - not paused"}"}""")
    }

    private fun executeDirectorCue(body: JSONObject?): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        body ?: return ApiHandlerUtils.bodyRequired()
        val cueText = body.optString("cue", "")
        if (cueText.isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, WebServer.MIME_JSON, """{"error": "cue field is required"}""")
        }
        val success = director.executeCueText(cueText)
        return NanoHTTPD.newFixedLengthResponse(
            if (success) Status.OK else Status.BAD_REQUEST, WebServer.MIME_JSON,
            """{"success": $success, "cue": "$cueText"}""")
    }

    private fun advanceDirectorCue(): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        val nextCue = director.advanceCue()
        val json = JSONObject().apply {
            put("success", nextCue != null)
            if (nextCue != null) {
                put("cue", JSONObject().apply {
                    put("type", nextCue.type.name)
                    put("rawText", nextCue.rawText)
                    put("lineNumber", nextCue.lineNumber)
                })
            } else { put("message", "No more cues") }
        }
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    private fun jumpToScene(body: JSONObject?): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        body ?: return ApiHandlerUtils.bodyRequired()
        val sceneIndex = body.optInt("sceneIndex", -1)
        if (sceneIndex < 0) {
            return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, WebServer.MIME_JSON, """{"error": "sceneIndex is required and must be >= 0"}""")
        }
        val success = director.jumpToScene(sceneIndex)
        return NanoHTTPD.newFixedLengthResponse(
            if (success) Status.OK else Status.BAD_REQUEST, WebServer.MIME_JSON,
            """{"success": $success, "sceneIndex": $sceneIndex}""")
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

    private fun markTake(body: JSONObject?): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        body ?: return ApiHandlerUtils.bodyRequired()
        val takeNumber = body.optInt("takeNumber", -1)
        val qualityStr = body.optString("quality", "")
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
            put("bestTake", if (comparison.bestTake != null) {
                JSONObject().apply {
                    put("takeNumber", comparison.bestTake.takeNumber)
                    put("qualityScore", comparison.bestTake.qualityScore)
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
        director.getTakeManager().linkTakeToFile(takeNumber, filePath)
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON,
            """{"success": true, "takeNumber": $takeNumber, "filePath": "$filePath"}""")
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
                put("state", session.state.name)
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

    private fun getDirectorEvents(): NanoHTTPD.Response {
        val director = directorManager ?: return ApiHandlerUtils.serviceUnavailable("Director service")
        val status = director.getStatus()
        val initialEvent = JSONObject().apply {
            put("type", "state")
            put("enabled", status.enabled)
            put("state", status.state.name)
            put("currentScene", status.currentScene ?: "")
            put("currentCue", status.currentCue ?: "")
            put("currentTake", status.takeNumber)
        }
        val sseData = "data: ${initialEvent.toString()}\n\n"
        return NanoHTTPD.newFixedLengthResponse(Status.OK, "text/event-stream", sseData).apply {
            addHeader("Cache-Control", "no-cache")
            addHeader("Connection", "keep-alive")
            addHeader("Access-Control-Allow-Origin", "*")
        }
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
            val director = directorManager
            val loadResult = director?.loadScript(content)
            val json = JSONObject().apply {
                put("success", true)
                put("fileName", fileName)
                put("content", content)
                put("loaded", loadResult?.isSuccess ?: false)
                if (loadResult?.isSuccess == true) {
                    val script = loadResult.getOrNull()!!
                    put("scenes", script.scenes.size)
                    put("totalCues", script.totalCues)
                }
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
            put("script", session.script.rawText)
            put("scenes", session.script.scenes.size)
            put("totalCues", session.script.totalCues)
        }
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

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
        var saved = false
        if (result.isSuccess && saveToFile && fileName.isNotEmpty()) {
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
        return if (result.isSuccess) {
            val script = result.getOrNull()!!
            val json = JSONObject().apply {
                put("success", true)
                put("message", "Script imported")
                put("scenes", script.scenes.size)
                put("totalCues", script.totalCues)
                put("saved", saved)
            }
            NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
        } else {
            Timber.tag(TAG).w("Script import failed: ${result.exceptionOrNull()?.message}")
            NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, WebServer.MIME_JSON, """{"success": false, "error": "Failed to parse script"}""")
        }
    }

    companion object {
        private const val TAG = "DirectorApiHandler"
    }
}
