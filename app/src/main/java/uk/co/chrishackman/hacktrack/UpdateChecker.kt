package uk.co.chrishackman.hacktrack

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val isAvailable: Boolean,
    val latestVersion: String = "",
    val downloadUrl: String = ""
)

object UpdateChecker {

    /**
     * Replace this with your actual raw GitHub version.json URL.
     * Example: https://raw.githubusercontent.com/EcoByteBasher/HackTrack/main/version.json
     */
    private const val VERSION_URL = 
        "https://raw.githubusercontent.com/EcoByteBasher/HackTrack/main/version.json"

    suspend fun checkForUpdate(context: Context): UpdateInfo {
        return withContext(Dispatchers.IO) {
            try {
                val currentVersion = context.packageManager
                    .getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"

                val connection = URL(VERSION_URL).openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()

                val json = JSONObject(response)
                val latestVersion = json.getString("version")
                val downloadUrl = json.getString("url")

                // Simple version comparison (e.g., "1.0.4" vs "1.0.5")
                UpdateInfo(
                    isAvailable = isNewer(currentVersion, latestVersion),
                    latestVersion = latestVersion,
                    downloadUrl = downloadUrl
                )
            } catch (e: Exception) {
                UpdateInfo(isAvailable = false)
            }
        }
    }

    private fun isNewer(current: String, latest: String): Boolean {
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }

        for (i in 0 until minOf(currentParts.size, latestParts.size)) {
            if (latestParts[i] > currentParts[i]) return true
            if (latestParts[i] < currentParts[i]) return false
        }
        return latestParts.size > currentParts.size
    }
}
