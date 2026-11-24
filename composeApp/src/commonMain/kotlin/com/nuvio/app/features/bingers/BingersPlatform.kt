package com.nuvio.app.features.bingers

/** Where each profile's Bingers access token is kept, encrypted by the platform. */
internal expect object PlatformBingersTokenPersistence {
    /** False until the platform storage can be read (Android: before the app initialised it). */
    fun isReady(): Boolean
    fun read(profileId: Int): String?
    fun write(profileId: Int, value: String?)
    fun clear()
}
