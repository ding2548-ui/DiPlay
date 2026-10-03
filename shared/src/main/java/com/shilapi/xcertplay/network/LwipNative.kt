// SPDX-License-Identifier: GPL-3.0-only
// JNI surface of the userspace lwIP stack that consumes the wired NCM ethernet frames
// directly (EasyPlay's LwipNative, ported verbatim). The JNI symbols are bound to this
// exact class name, so the package cannot change.
//
// The library ships for armeabi-v7a only; loading fails on a 64-bit process, which
// [available] reports instead of crashing the attach path.
package com.shilapi.xcertplay.network

object LwipNative {
    const val STREAM = 1
    const val DGRAM = 2

    @Volatile
    var available: Boolean = false
        private set

    init {
        runCatching { System.loadLibrary("diplay_lwip") }
            .onSuccess { available = true }
            .onFailure { android.util.Log.w("DiPlay-Lwip", "lwIP native library unavailable: ${it.message}") }
    }

    external fun start(hostMac: ByteArray): Long

    external fun getLocalAddress(handle: Long): ByteArray

    external fun input(handle: Long, frame: ByteArray): Boolean

    external fun pollOutput(handle: Long, maxBytes: Int): ByteArray?

    external fun socket(handle: Long, type: Int): Int

    external fun close(handle: Long, fd: Int)

    external fun bind(handle: Long, fd: Int, address: ByteArray, port: Int)

    external fun listen(handle: Long, fd: Int, backlog: Int)

    external fun accept(handle: Long, fd: Int): Int

    external fun connect(handle: Long, fd: Int, address: ByteArray, port: Int, timeoutSeconds: Int)

    external fun read(handle: Long, fd: Int, buffer: ByteArray, offset: Int, length: Int): Int

    external fun write(handle: Long, fd: Int, buffer: ByteArray, offset: Int, length: Int): Int

    external fun shutdown(handle: Long, fd: Int, how: Int)

    external fun recvfrom(handle: Long, fd: Int, buffer: ByteArray, offset: Int, length: Int, address: ByteArray, aux: IntArray): Int

    external fun sendto(handle: Long, fd: Int, buffer: ByteArray, offset: Int, length: Int, address: ByteArray, port: Int): Int

    external fun getLocalPort(handle: Long, fd: Int): Int

    external fun getPeerAddress(handle: Long, fd: Int): ByteArray

    external fun getPeerPort(handle: Long, fd: Int): Int

    external fun setTimeout(handle: Long, fd: Int, seconds: Int)

    external fun setKeepAlive(handle: Long, fd: Int, enabled: Boolean)

    external fun setTcpNoDelay(handle: Long, fd: Int, enabled: Boolean)

    external fun setSoLinger(handle: Long, fd: Int, enabled: Boolean, seconds: Int)

    external fun setReuseAddress(handle: Long, fd: Int, enabled: Boolean)

    external fun setReceiveBufferSize(handle: Long, fd: Int, bytes: Int)

    external fun getReceiveBufferSize(handle: Long, fd: Int): Int

    external fun getSendBufferSize(handle: Long, fd: Int): Int

    external fun joinGroup(handle: Long, address: ByteArray)

    external fun leaveGroup(handle: Long, address: ByteArray)

    external fun stop(handle: Long)
}
