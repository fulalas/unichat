package org.unichat.app

object TdJson {
    init {
        System.loadLibrary("tdjson")
        System.loadLibrary("tdjni")
    }

    @JvmStatic external fun createClientId(): Int
    @JvmStatic private external fun send(clientId: Int, request: ByteArray)
    @JvmStatic private external fun receive(timeout: Double): ByteArray?

    fun send(clientId: Int, request: String) = send(clientId, request.toByteArray())

    fun receiveString(timeout: Double): String? = receive(timeout)?.let { String(it) }
}
