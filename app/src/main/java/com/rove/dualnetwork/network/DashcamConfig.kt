package com.rove.dualnetwork.network

/**
 * Single source of truth for dashcam-specific configuration.
 *
 * The active camera model is held in [selected] and changed via the
 * model-picker dropdown in the UI. Everything that needs the current
 * dashcam IP / base URL / SSID-match rule reads from here, so adding
 * a new camera = one new row in [CameraModel] + an extra cleartext
 * `<domain>` entry in `res/xml/network_security_config.xml` (XML can't
 * reference Kotlin constants).
 */
object DashcamConfig {

    /**
     * Currently selected camera model. Mutated by
     * `MainViewModel.selectCamera(...)` when the user picks from the
     * dropdown. `@Volatile` so other threads see the new value immediately
     * — the network/wifi callbacks run off the main thread.
     */
    @Volatile
    var selected: CameraModel = CameraModel.ROVE_R2_4K_DUAL_PRO

    /** Dashcam HTTP server IP for the [selected] model. */
    val host: String get() = selected.host

    /** Retrofit base URL for the [selected] model — always trailing slash. */
    val baseUrl: String get() = selected.baseUrl

    /** Human-readable label for the [selected] model. */
    val displayName: String get() = selected.displayName

    /** Does [ssid] belong to the [selected] camera? */
    fun matchesSsid(ssid: String): Boolean = selected.matchesSsid(ssid)

    /** Factory passphrase shipped on every Rove camera. */
    const val PASSPHRASE: String = "12345678"
}
