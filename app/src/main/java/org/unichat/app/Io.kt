package org.unichat.app

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object Io {
    val executor: ExecutorService = Executors.newSingleThreadExecutor()

    val files: ExecutorService = Executors.newSingleThreadExecutor()

    val lookup: ExecutorService = Executors.newSingleThreadExecutor()
}
