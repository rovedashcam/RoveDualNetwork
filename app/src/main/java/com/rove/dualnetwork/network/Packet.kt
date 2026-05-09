package com.rove.dualnetwork.network

import java.io.FileOutputStream
import java.net.InetAddress

/**
 * IP / TCP / UDP packet builder and field reader.
 * All integers are big-endian (network byte order).
 */
object Packet {

    // ── Field readers ─────────────────────────────────────────────────────────

    fun readInt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
        ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    fun readU16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    fun readU32(b: ByteArray, o: Int): Long =
        readInt(b, o).toLong() and 0xFFFFFFFFL

    private fun writeInt(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte()
        b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }

    fun writeU16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 8).toByte(); b[o + 1] = v.toByte()
    }

    fun intToAddr(ip: Int): InetAddress = InetAddress.getByAddress(
        byteArrayOf((ip ushr 24).toByte(), (ip ushr 16).toByte(), (ip ushr 8).toByte(), ip.toByte())
    )

    fun intToStr(ip: Int) =
        "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"

    // ── Packet builders ───────────────────────────────────────────────────────

    fun sendRst(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int, seq: Long, out: FileOutputStream) =
        out.write(tcp(dstIp, srcIp, dstPort, srcPort, 0L, seq, TCP_RST, ByteArray(0)))

    /**
     * Build a complete IPv4 + TCP packet.
     * [flags] e.g. TCP_SYN_ACK, TCP_ACK, TCP_PSH_ACK, TCP_FIN_ACK, TCP_RST
     */
    fun tcp(
        srcIp: Int, dstIp: Int, srcPort: Int, dstPort: Int,
        seq: Long, ack: Long, flags: Int, payload: ByteArray
    ): ByteArray {
        val tcpLen = TCP_HEADER + payload.size
        val total  = IP_HEADER  + tcpLen
        val b = ByteArray(total)          // zero-initialized

        // ── IPv4 header ───────────────────────────────────────────────────────
        b[0] = 0x45.toByte()              // version=4, IHL=5 (20 bytes)
        writeU16(b, 2, total)
        writeU16(b, 6, 0x4000)            // DF flag, no fragmentation
        b[8] = 64                         // TTL
        b[9] = 6                          // protocol: TCP
        writeInt(b, 12, srcIp)
        writeInt(b, 16, dstIp)
        writeU16(b, 10, ipChecksum(b))

        // ── TCP header ────────────────────────────────────────────────────────
        val t = IP_HEADER
        writeU16(b, t,     srcPort)
        writeU16(b, t + 2, dstPort)
        writeInt(b, t + 4, (seq and 0xFFFFFFFFL).toInt())
        writeInt(b, t + 8, (ack and 0xFFFFFFFFL).toInt())
        b[t + 12] = 0x50.toByte()         // data offset = 5 × 4 = 20 bytes
        b[t + 13] = flags.toByte()
        writeU16(b, t + 14, 65535)        // receive window
        System.arraycopy(payload, 0, b, t + TCP_HEADER, payload.size)
        writeU16(b, t + 16, tcpChecksum(b, srcIp, dstIp, tcpLen))
        return b
    }

    /** Build a complete IPv4 + UDP packet (response direction: src is the remote). */
    fun udp(srcIp: Int, dstIp: Int, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val total  = IP_HEADER + udpLen
        val b = ByteArray(total)

        b[0] = 0x45.toByte()
        writeU16(b, 2, total)
        writeU16(b, 6, 0x4000)
        b[8] = 64
        b[9] = 17                         // protocol: UDP
        writeInt(b, 12, srcIp)
        writeInt(b, 16, dstIp)
        writeU16(b, 10, ipChecksum(b))

        val u = IP_HEADER
        writeU16(b, u,     srcPort)
        writeU16(b, u + 2, dstPort)
        writeU16(b, u + 4, udpLen)
        System.arraycopy(payload, 0, b, u + 8, payload.size)
        return b
    }

    // ── Checksums ─────────────────────────────────────────────────────────────

    private fun ipChecksum(b: ByteArray): Int = ones(b, 0, IP_HEADER)

    private fun tcpChecksum(b: ByteArray, srcIp: Int, dstIp: Int, tcpLen: Int): Int {
        // Pseudo-header: src IP, dst IP, 0x00, proto(6), TCP length
        val ph = ByteArray(12 + tcpLen)
        writeInt(ph, 0, srcIp)
        writeInt(ph, 4, dstIp)
        ph[9] = 6
        writeU16(ph, 10, tcpLen)
        System.arraycopy(b, IP_HEADER, ph, 12, tcpLen)
        ph[28] = 0; ph[29] = 0            // zero out the checksum field in copy
        return ones(ph, 0, ph.size)
    }

    private fun ones(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i < off + len - 1) { sum += readU16(b, i); i += 2 }
        if (len % 2 != 0) sum += (b[off + len - 1].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.toInt().inv() and 0xFFFF
    }

    // ── TCP flag constants ────────────────────────────────────────────────────

    const val TCP_SYN     = 0x02
    const val TCP_RST     = 0x04
    const val TCP_ACK     = 0x10
    const val TCP_SYN_ACK = 0x12  // SYN + ACK
    const val TCP_PSH_ACK = 0x18  // PSH + ACK  (data)
    const val TCP_FIN_ACK = 0x11  // FIN + ACK

    private const val IP_HEADER  = 20
    private const val TCP_HEADER = 20
}
