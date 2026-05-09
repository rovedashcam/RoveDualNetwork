package com.rove.dualnetwork.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET

/**
 * Internet connectivity test.
 * Routed via cellular socket → google.com (never touches dashcam WiFi).
 */
interface InternetApiService {

    /**
     * Fetch google.com to prove cellular internet is working.
     * Returns raw HTML so we can display status + a snippet.
     */
    @GET("/")
    suspend fun fetchGoogle(): Response<ResponseBody>
}
