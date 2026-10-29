package com.example.fatiguesense.enterprise.enterprise

import java.net.URI

/** The native client connects directly; it does not share the web's Vite proxy. */
object BackendAddress {
    fun apiBase(value: String, debug: Boolean): String {
        val uri = URI(value.trim())
        require(uri.scheme == "https" || debug && uri.scheme == "http") {
            "Backend must use HTTPS. Local HTTP is allowed only in debug builds."
        }
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "Enter the backend server address without credentials, query parameters or fragments."
        }
        val path = uri.path.orEmpty().trimEnd('/')
        require(path.isEmpty() || path == "/api") { "Backend address must end in /api." }
        return value.trim().trimEnd('/').let { if (path.isEmpty()) "$it/api" else it }
    }
}
