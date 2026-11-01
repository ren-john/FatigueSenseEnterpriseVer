package com.example.fatiguesense.enterprise.enterprise

/** Captured once at start, retained through reconnect and final event persistence. */
sealed interface MonitoringContext {
    data class Local(val deviceId: String) : MonitoringContext
    data class Enterprise(val session: DriverSession) : MonitoringContext
}

internal fun MonitoringContext.requireMode(mode: AppMode, owner: CloudOwner?) {
    when (this) {
        is MonitoringContext.Local -> check(mode == AppMode.LOCAL) { "Local monitoring mode changed." }
        is MonitoringContext.Enterprise -> check(mode == AppMode.ENTERPRISE &&
            owner == CloudOwner(session.driver.uid, session.driver.orgId)) { "Enterprise monitoring owner changed." }
    }
}
