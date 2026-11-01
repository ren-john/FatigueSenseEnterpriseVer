package com.example.fatiguesense.enterprise.enterprise

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Closure must not wait for detection uploads: a new server session needs the old one released. */
object SessionClosures {
    private val lock = Mutex()
    suspend fun sync(dao: EnterpriseDao, driver: Driver) = sync(
        pending = { dao.pendingClosures(driver.uid, driver.orgId) },
        close = { ended ->
            val result = EnterpriseAuth.call("mobileEndSession", JSONObject()
                .put("sessionId", ended.sessionId).put("endedAt", ended.endedAt), driver)
            check(result.optBoolean("success")) { "Session closure not acknowledged. Retry before starting monitoring." }
        },
        acknowledge = { dao.acknowledgeSession(it.sessionId) },
    )

    internal suspend fun sync(pending: suspend () -> List<LocalSession>, close: suspend (LocalSession) -> Unit,
                              acknowledge: suspend (LocalSession) -> Unit) = lock.withLock {
        for (ended in pending()) {
            close(ended)
            acknowledge(ended)
        }
    }
}
