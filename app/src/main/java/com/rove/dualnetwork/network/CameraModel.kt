package com.rove.dualnetwork.network

/**
 * One enum per supported Rove dashcam model. The active model is picked at
 * runtime through [DashcamConfig.selected]; everything else (URL, SSID
 * matcher, on-screen label) is read from this enum so adding a new model
 * is one line in [CameraModel] + one cleartext entry in
 * `network_security_config.xml`.
 */
enum class CameraModel(
    val displayName: String,
    val host: String
) {
    R3(
        displayName = "R3",
        host        = "192.168.1.248"
    ),
    ROVE_R2_4K_DUAL_PRO(
        displayName = "ROVE_R2-4K-DUAL-PRO",
        host        = "192.168.1.253"
    ),
    ROVE_R2_4K_DUAL(
        displayName = "ROVE_R2-4K-DUAL",
        host        = "192.168.1.252"
    );

    /** `http://<host>/` — used as the Retrofit base URL. */
    val baseUrl: String get() = "http://$host/"

    /**
     * Returns true if [ssid] is broadcast by *this* camera model. Each model
     * has its own match rule — the non-Pro Rove SSID is a prefix of the Pro
     * one, so we explicitly disambiguate.
     */
    fun matchesSsid(ssid: String): Boolean = when (this) {
        R3 ->
            ssid.contains("R3Sigma", ignoreCase = true)
        ROVE_R2_4K_DUAL_PRO ->
            ssid.startsWith("ROVE_R2-4K-DUAL-PRO")
        ROVE_R2_4K_DUAL ->
            ssid.startsWith("ROVE_R2-4K-DUAL") &&
            !ssid.startsWith("ROVE_R2-4K-DUAL-PRO")
    }
}
