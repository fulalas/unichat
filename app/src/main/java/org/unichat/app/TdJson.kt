package org.unichat.app

/**
 * Raw binding to TDLib's JSON interface (libtdjson.so via the libtdjni.so
 * shim). Byte arrays on both directions — see tdjson/jni/tdjni.c.
 */
object TdJson {
    init {
        System.loadLibrary("tdjson")
        System.loadLibrary("tdjni")
    }

    @JvmStatic external fun createClientId(): Int
    @JvmStatic private external fun send(clientId: Int, request: ByteArray)
    @JvmStatic private external fun receive(timeout: Double): ByteArray?
    @JvmStatic private external fun execute(request: ByteArray): ByteArray?

    fun send(clientId: Int, request: String) = send(clientId, request.toByteArray())

    /** Blocks up to [timeout] seconds; one dedicated thread only (see shim). */
    fun receiveString(timeout: Double): String? = receive(timeout)?.let { String(it) }

    fun executeString(request: String): String? = execute(request.toByteArray())?.let { String(it) }
}
