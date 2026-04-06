package com.rokidapks

object RokidSdkAuth {
    const val bleServiceUuid = "00009100-0000-1000-8000-00805f9b34fb"
    const val uploadPath = "/server/upload"

    val clientSecret: String
        get() = BuildConfig.ROKID_CLIENT_SECRET.trim()

    val authBlobName: String
        get() = BuildConfig.ROKID_AUTH_BLOB_NAME.trim()

    val clientSecretToken: String?
        get() = clientSecret
            .takeIf { it.isNotBlank() }
            ?.replace("-", "")
            ?.takeIf { it.isNotBlank() }
}
