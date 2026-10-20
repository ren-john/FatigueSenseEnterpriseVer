package com.example.fatiguesense.enterprise.ml

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin

/** Native calls can outlive cancellation; their resources must outlive them. */
internal suspend fun retirePipeline(job: Job?, release: () -> Unit) {
    job?.cancelAndJoin()
    release()
}
