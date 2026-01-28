package org.onekash.icaldav.client

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Await the result of an OkHttp Call as a suspend function.
 *
 * This properly integrates with coroutine cancellation - if the coroutine
 * is cancelled, the HTTP call is also cancelled.
 *
 * Usage:
 * ```kotlin
 * val response = httpClient.newCall(request).await()
 * response.use { r ->
 *     // Process response - MUST close when done
 * }
 * ```
 *
 * @return Response which caller MUST close (use response.use { } pattern)
 * @throws IOException on network failure
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation {
        cancel() // Cancel the HTTP call if coroutine is cancelled
    }

    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }
    })
}
