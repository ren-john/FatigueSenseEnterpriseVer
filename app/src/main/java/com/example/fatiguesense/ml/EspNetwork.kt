package com.example.fatiguesense.enterprise.ml

import android.net.Network

/** ESP sockets must never fall back to the cellular/default network. */
internal object EspNetwork {
    @Volatile var network: Network? = null
}
