package com.rove.dualnetwork.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Dashcam HTTP API.
 * Routed via WiFi socket → 192.168.1.253 (never touches cellular).
 */
interface DashcamApiService {

    /**
     * Send a command to the dashcam.
     * Example: GET http://192.168.1.253/?custom=1&cmd=1008&par=2
     * Returns the raw response body so any format (XML, JSON, plain text) is captured.
     */
    @GET("/")
    suspend fun sendCommand(
        @Query("custom") custom: Int,
        @Query("cmd")    cmd:    Int,
        @Query("par")    par:    Int
    ): Response<ResponseBody>
}
