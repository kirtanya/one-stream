package com.kirtanya.reel.core.tvremote

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

/**
 * A tiny protobuf writer / reader for the Android TV Remote protocol v2 (the Google TV / Google Home
 * remote): varints and length-delimited fields are all it uses. Messages are framed by a varint length.
 * Same wire code as One Launcher's remote (one_launcher TvRemote.kt), which is proven on the user's TV.
 */
class Pb {
    private val b = ByteArrayOutputStream()

    fun int(field: Int, v: Long) = apply { writeVarint(b, (field shl 3).toLong()); writeVarint(b, v) }
    fun bytes(field: Int, data: ByteArray) = apply { writeVarint(b, ((field shl 3) or 2).toLong()); writeVarint(b, data.size.toLong()); b.write(data) }
    fun str(field: Int, s: String) = bytes(field, s.toByteArray())
    fun msg(field: Int, m: Pb) = bytes(field, m.bytes())
    fun bytes(): ByteArray = b.toByteArray()

    companion object {
        fun writeVarint(o: ByteArrayOutputStream, value: Long) {
            var v = value
            while (true) {
                if (v and 0x7fL.inv() == 0L) { o.write(v.toInt()); return }
                o.write(((v and 0x7f) or 0x80).toInt())
                v = v ushr 7
            }
        }

        /** field number → values (Long for varints, ByteArray for length-delimited). */
        fun parse(data: ByteArray): Map<Int, List<Any>> {
            val out = mutableMapOf<Int, MutableList<Any>>()
            var p = 0
            fun varint(): Long {
                var r = 0L
                var s = 0
                while (true) {
                    val x = data[p++].toInt() and 0xff
                    r = r or ((x and 0x7f).toLong() shl s)
                    if (x and 0x80 == 0) return r
                    s += 7
                }
            }
            while (p < data.size) {
                val tag = varint()
                val field = (tag ushr 3).toInt()
                when ((tag and 7).toInt()) {
                    0 -> out.getOrPut(field) { mutableListOf() }.add(varint())
                    2 -> {
                        val n = varint().toInt()
                        out.getOrPut(field) { mutableListOf() }.add(data.copyOfRange(p, p + n))
                        p += n
                    }
                    1 -> p += 8
                    5 -> p += 4
                    else -> return out
                }
            }
            return out
        }

        fun send(o: OutputStream, m: Pb) {
            val body = m.bytes()
            val frame = ByteArrayOutputStream()
            writeVarint(frame, body.size.toLong())
            frame.write(body)
            o.write(frame.toByteArray())
            o.flush()
        }

        fun read(i: InputStream): Map<Int, List<Any>> {
            var len = 0L
            var shift = 0
            while (true) {
                val b = i.read()
                if (b < 0) throw EOFException()
                len = len or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            val buf = ByteArray(len.toInt())
            var got = 0
            while (got < buf.size) {
                val n = i.read(buf, got, buf.size - got)
                if (n < 0) throw EOFException()
                got += n
            }
            return parse(buf)
        }
    }
}

/** The pairing answer: SHA-256 over both RSA keys and the TV's code; the code's first byte must match the hash's. */
object RemotePairing {
    /** Null when [code] isn't 6 hex characters or doesn't match these two keys (a typo). */
    fun secret(code: String, client: RSAPublicKey, server: RSAPublicKey): ByteArray? {
        val c = code.trim().uppercase()
        if (c.length != 6 || !c.all { it.isDigit() || it in 'A'..'F' }) return null
        val sha = MessageDigest.getInstance("SHA-256")
        sha.update(unsigned(client.modulus)); sha.update(unsigned(client.publicExponent))
        sha.update(unsigned(server.modulus)); sha.update(unsigned(server.publicExponent))
        sha.update(hex(c.substring(2)))
        val hash = sha.digest()
        return hash.takeIf { (it[0].toInt() and 0xff) == c.substring(0, 2).toInt(16) }
    }

    private fun unsigned(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** Key codes the cast flow sends (android.view.KeyEvent values). */
object RemoteKeys {
    /** Wakes the TV; unlike POWER it never turns a TV off, so it's always safe to send. */
    const val WAKEUP = 224
    const val POWER = 26
    /** Press + release in one message. */
    const val SHORT = 3
}
