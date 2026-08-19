package org.unichat.app

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Shared serial worker for activity-side DB reads and small file staging.
 * Activities used to each own a single-thread executor that was never shut
 * down, leaking one idle thread per activity instance; one app-wide thread
 * serves the same per-caller ordering needs without the leak.
 */
object Io {
    val executor: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * Bulk file work (staging shared attachments), kept off [executor]. Sharing
     * N items to M chats performs N*(M+1) whole-file copies; running those on
     * the serial worker every screen's DB reads share blocked the chat list and
     * the open chat for the entire duration of the share.
     */
    val files: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * Number/chat-id lookups only: they block on the network far longer (up to
     * 75s) than any list read, and sharing [executor] would stall every screen
     * behind one of them.
     */
    val lookup: ExecutorService = Executors.newSingleThreadExecutor()
}
