package com.omiyawaki.osrswiki.network.interceptor

import android.util.Log
import com.omiyawaki.osrswiki.database.OfflineAsset
import com.omiyawaki.osrswiki.database.OfflineAssetDao
import com.omiyawaki.osrswiki.offline.storage.FileStorageManager
import kotlinx.coroutines.flow.firstOrNull // Import for .firstOrNull()
import kotlinx.coroutines.runBlocking // For synchronous execution of suspend DAO functions
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.io.IOException

class OfflineAssetInterceptor(
    private val offlineAssetDao: OfflineAssetDao, // Injected DAO
    private val fileStorageManager: FileStorageManager // Injected FileStorageManager
) : Interceptor {

    companion object {
        private const val TAG = "OsrsOfflineInterceptor"
        const val HEADER_SAVE_ASSET = "X-OSRSWiki-Save-Asset"
        const val HEADER_SAVED_ARTICLE_ENTRY_ID = "X-OSRSWiki-SavedArticleEntry-Id"
        const val HEADER_ASSET_ORIGINAL_URL = "X-OSRSWiki-Asset-Original-Url"
        const val HEADER_ASSET_TYPE = "X-OSRSWiki-Asset-Type" // e.g., "html", "image"
    }

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        val shouldSaveAsset = request.header(HEADER_SAVE_ASSET)?.toBooleanStrictOrNull() ?: false
        val savedArticleEntryIdString = request.header(HEADER_SAVED_ARTICLE_ENTRY_ID)
        val assetOriginalUrl = request.header(HEADER_ASSET_ORIGINAL_URL)
        val assetType = request.header(HEADER_ASSET_TYPE) // Can be null, handled by FileStorageManager

        if (!shouldSaveAsset || savedArticleEntryIdString == null || assetOriginalUrl == null) {
            // Not a request designated for offline saving, or missing critical info. Proceed normally.
            return chain.proceed(request)
        }

        val savedArticleEntryId = savedArticleEntryIdString.toLongOrNull()
        if (savedArticleEntryId == null) {
            Log.e(TAG, "Invalid SavedArticleEntry ID received: $savedArticleEntryIdString for URL: $assetOriginalUrl. Skipping offline save.")
            return chain.proceed(request)
        }

        val originalResponse: Response
        try {
            originalResponse = chain.proceed(request)
        } catch (e: IOException) {
            Log.w(TAG, "Network request failed for $assetOriginalUrl. Cannot save asset.", e)
            throw e // Propagate network errors
        }

        val responseBody = originalResponse.body
        if (!originalResponse.isSuccessful || responseBody == null) {
            Log.w(TAG, "Network request for $assetOriginalUrl was not successful or had no body. Code: ${originalResponse.code}. Skipping offline save.")
            return originalResponse // Return original unsuccessful response
        }

        var savedFile: File? = null
        try {
            // Save the asset using FileStorageManager. This consumes the original response's InputStream.
            val actualSavedFile = fileStorageManager.saveAsset( // Renamed to avoid conflict if savedFile is null later
                inputStream = responseBody.source().inputStream(), // Read as a stream
                originalUrl = assetOriginalUrl,
                assetType = assetType ?: "unknown",
                contentType = responseBody.contentType()?.toString()
            )
            savedFile = actualSavedFile // Assign to outer scope variable for finally block
            val localFilePath = actualSavedFile.absolutePath

            // IMPORTANT: DAO operations are often suspend functions.
            // Running them blocking on an OkHttp dispatcher thread is generally not recommended for long operations.
            // For this example, using runBlocking for simplicity, assuming DAO ops are quick.
            // In a production app, consider dispatching DB work to a dedicated coroutine scope or service.
            runBlocking {
                // Collect the OfflineAsset? from the Flow
                val assetFromDb: OfflineAsset? = offlineAssetDao.getAssetByOriginalUrl(assetOriginalUrl).firstOrNull()
                val currentTime = System.currentTimeMillis()

                if (assetFromDb == null) {
                    val newUsedBySet = mutableSetOf(savedArticleEntryId.toString())
                    val newOfflineAsset = OfflineAsset(
                        originalUrl = assetOriginalUrl,
                        localFilePath = localFilePath,
                        downloadTimestamp = currentTime,
                        usedByArticleIds = newUsedBySet.joinToString(",")
                    )
                    offlineAssetDao.insert(newOfflineAsset)
                    Log.i(TAG, "Inserted new OfflineAsset for $assetOriginalUrl, linked to entry $savedArticleEntryId")
                } else {
                    // assetFromDb is not null here
                    val existingUsedBySet = assetFromDb.usedByArticleIds
                        .split(',')
                        .filter { it.isNotBlank() }
                        .toMutableSet()

                    val updated = existingUsedBySet.add(savedArticleEntryId.toString())

                    assetFromDb.localFilePath = localFilePath // Update path in case it was re-downloaded/moved
                    assetFromDb.downloadTimestamp = currentTime
                    if (updated) {
                        assetFromDb.usedByArticleIds = existingUsedBySet.joinToString(",")
                    }
                    offlineAssetDao.update(assetFromDb) // Pass the modified OfflineAsset object
                    Log.i(TAG, "Updated existing OfflineAsset for $assetOriginalUrl, linked/re-linked to entry $savedArticleEntryId. Used by: ${assetFromDb.usedByArticleIds}")
                }
            }

            // Since the original response body's stream was consumed by saveAsset,
            // we must return a new response with a body that reads from the saved file.
            val fileResponseBody = actualSavedFile.readBytes().toResponseBody(responseBody.contentType())
            return originalResponse.newBuilder().body(fileResponseBody).build()

        } catch (e: Exception) {
            Log.e(TAG, "Error during asset saving or DB update for $assetOriginalUrl", e)
            // If saving failed, attempt to delete any partially saved file to avoid corruption.
            savedFile?.let {
                if (it.exists()) {
                    Log.d(TAG, "Attempting to delete partially saved file: ${it.absolutePath}")
                    fileStorageManager.deleteFile(it.absolutePath)
                }
            }
            // Return the original network response. The app will function as if the asset wasn't saved for offline.
            // Do not re-throw an IOException here if the originalResponse itself was successful,
            // as that would incorrectly signal a network failure to the caller.
            return originalResponse
        }
    }
}
