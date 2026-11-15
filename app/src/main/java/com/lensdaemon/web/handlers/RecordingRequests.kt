package com.lensdaemon.web.handlers

import com.lensdaemon.storage.RecordingFile
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.URLEncoder

/**
 * Checks for requests that name a recording: by file name (download and
 * delete) or by path (upload enqueue).
 *
 * A request only ever reaches a file that the recordings list holds, so it
 * can't touch other app files, whatever characters the name carries.
 * Recordings made on phones whose model name has characters such as '(' or
 * in a locale with non-Latin digits therefore stay reachable.
 */
internal object RecordingRequests {

    /** Characters replaced by '_' in the plain `filename=` of a download. */
    private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")

    /** Characters that make a name a path rather than a bare file name. */
    private val PATH_CHARS = charArrayOf('/', '\\', '\u0000')

    /**
     * True for a bare file name: not empty, with no '/', backslash, ".." or
     * NUL. Any other character is accepted; the name must still match a
     * listed recording before anything is done with it.
     */
    fun isPlainFileName(name: String): Boolean =
        name.isNotEmpty() && !name.contains("..") && name.none { it in PATH_CHARS }

    /**
     * The listed recording that [filePath] names (compared by canonical path,
     * so "/dir/./x.mp4" matches "/dir/x.mp4"), or, without a path, the one
     * whose name is exactly [name]. Null when they name no listed recording.
     */
    fun find(recordings: List<RecordingFile>, filePath: String?, name: String?): RecordingFile? {
        if (filePath != null) {
            return recordings.firstOrNull { isSameFile(it.path, filePath) }
        }
        if (name == null || !isPlainFileName(name)) return null
        return recordings.firstOrNull { it.name == name }
    }

    /** True if [a] and [b] are the same file by canonical path; false if either is null or can't be resolved. */
    fun isSameFile(a: String?, b: String?): Boolean {
        val first = a?.let { canonicalPath(it) } ?: return false
        val second = b?.let { canonicalPath(it) } ?: return false
        return first == second
    }

    /**
     * Content-Disposition for downloading [name]: a `filename` reduced to
     * letters, digits, '.', '_' and '-' for any client, plus the exact name
     * percent-encoded as UTF-8 in `filename*` (RFC 6266).
     */
    fun contentDisposition(name: String): String {
        val plain = name.replace(UNSAFE_NAME_CHARS, "_")
        // URLEncoder writes spaces as '+' and leaves '*', which RFC 5987 doesn't allow unencoded
        val encoded = URLEncoder.encode(name, Charsets.UTF_8.name())
            .replace("+", "%20")
            .replace("*", "%2A")
        return "attachment; filename=\"$plain\"; filename*=UTF-8''$encoded"
    }

    private fun canonicalPath(path: String): String? = try {
        File(path).canonicalPath
    } catch (e: IOException) {
        Timber.w(e, "Cannot resolve the path $path")
        null
    }
}
