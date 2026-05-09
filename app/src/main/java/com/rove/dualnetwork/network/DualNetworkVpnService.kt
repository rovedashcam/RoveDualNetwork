package com.rove.dualnetwork.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Network
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Routes other apps' internet traffic through cellular while this app talks
 * directly to the dashcam WiFi (addDisallowedApplication keeps our sockets
 * out of the tunnel).
 *
 * Three bugs fixed vs the previous version:
 *
 * 1. MTU overflow — readFromRemote() was writing 16 KB IP packets to the TUN,
 *    but the TUN MTU is 1500 bytes.  Android returns EMSGSIZE; the catch-all
 *    swallowed it silently and sent FIN.  Browser got a FIN with no data for
 *    every page load.  Fix: never write more than MSS (1460) bytes of payload
 *    per TUN write; split larger server reads into multiple packets.
 *
 * 2. IPv6 route captured but dropped — Chrome's IPv6 SYNs went into the
 *    tunnel, got no reply, and Chrome waited ~1 s (Happy Eyeballs) before
 *    falling back to IPv4.  Fix: remove addRoute("::", 0).  IPv6 goes through
 *    the dashcam WiFi which has no internet; the gateway returns ICMP
 *    unreachable immediately and Chrome retries on IPv4 right away.
 *
 * 3. Browser FIN fully closed the relay socket — any server data still in
 *    flight was lost.  Fix: use socket.shutdownOutput() (half-close) so the
 *    relay continues draining the server response to the browser.
 */
class DualNetworkVpnService : VpnService() {

    companion object {
        private const val TAG        = "RoveVpnService"
        private const val CHANNEL_ID = "rove_vpn"
        private const val NOTIF_ID   = 9001
        const val ACTION_STOP        = "com.rove.dualnetwork.VPN_STOP"

        /** Maximum TCP payload per TUN write = MTU 1500 − IP hdr 20 − TCP hdr 20. */
        private const val MSS = 1460

        @Volatile var cellularNetwork: Network? = null
    }

    @Volatile private var running = false
    private var tunFd: ParcelFileDescriptor? = null
    private var pool: ExecutorService = Executors.newCachedThreadPool()

    private val tcpSessions = ConcurrentHashMap<String, TcpSession>()
    private val udpSessions = ConcurrentHashMap<String, UdpSession>()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { teardown(); return START_NOT_STICKY }
        if (!running) setup()
        return START_STICKY
    }

    override fun onRevoke() = teardown()
    override fun onDestroy() = teardown()

    // ── Setup / teardown ──────────────────────────────────────────────────────

    private fun setup() {
        createChannel()
        startForeground(NOTIF_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        tunFd = Builder()
            .setSession("Rove Dual Network")
            .addAddress("10.133.133.1", 32)
            .addDnsServer("8.8.8.8")
            .addDnsServer("8.8.4.4")
            .addRoute("0.0.0.0", 0)          // capture all IPv4
            // No IPv6 route: dashcam WiFi rejects IPv6 with ICMP immediately,
            // so Chrome falls back to IPv4 through our relay without delay.
            .addDisallowedApplication(packageName)   // our app bypasses VPN
            .setMtu(1500)
            .establish()

        if (tunFd == null) { Log.e(TAG, "establish() returned null"); stopSelf(); return }

        pool = Executors.newCachedThreadPool()   // fresh pool on every start
        running = true
        pool.submit(::packetLoop)
        Log.i(TAG, "VPN started — routing other apps through cellular")
    }

    private fun teardown() {
        if (!running) return
        running = false
        tunFd?.close(); tunFd = null        // unblocks packetLoop's read()
        tcpSessions.values.forEach { it.close() }; tcpSessions.clear()
        udpSessions.values.forEach { it.close() }; udpSessions.clear()
        pool.shutdownNow()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "VPN stopped")
    }

    // ── Packet loop ───────────────────────────────────────────────────────────

    private fun packetLoop() {
        val input  = FileInputStream(tunFd!!.fileDescriptor)
        val output = FileOutputStream(tunFd!!.fileDescriptor)
        val buf    = ByteArray(65535)

        while (running) {
            val len = try { input.read(buf) } catch (_: Exception) { break }
            if (len < 20) continue

            if ((buf[0].toInt() ushr 4) and 0xF != 4) continue   // IPv6 → ignore

            val proto    = buf[9].toInt() and 0xFF
            val srcIp    = Packet.readInt(buf, 12)
            val dstIp    = Packet.readInt(buf, 16)
            val ipHdrLen = (buf[0].toInt() and 0xF) * 4

            try {
                when (proto) {
                    6  -> handleTcp(buf, ipHdrLen, len, srcIp, dstIp, output)
                    17 -> handleUdp(buf, ipHdrLen, len, srcIp, dstIp, output)
                }
            } catch (e: Exception) {
                Log.w(TAG, "packet error proto=$proto", e)
            }
        }
    }

    // ── TCP ───────────────────────────────────────────────────────────────────

    private fun handleTcp(
        buf: ByteArray, ipHdr: Int, total: Int,
        srcIp: Int, dstIp: Int, out: FileOutputStream
    ) {
        val srcPort   = Packet.readU16(buf, ipHdr)
        val dstPort   = Packet.readU16(buf, ipHdr + 2)
        val seq       = Packet.readU32(buf, ipHdr + 4)
        val tcpHdrLen = ((buf[ipHdr + 12].toInt() ushr 4) and 0xF) * 4
        val flags     = buf[ipHdr + 13].toInt() and 0xFF
        val isSyn     = flags and Packet.TCP_SYN != 0
        val isAck     = flags and Packet.TCP_ACK != 0
        val isFin     = flags and 0x01 != 0
        val isRst     = flags and 0x04 != 0
        val payOff    = ipHdr + tcpHdrLen
        val payLen    = total - payOff
        val key       = "$srcIp:$srcPort>$dstIp:$dstPort"

        when {
            isSyn && !isAck -> {
                val net = cellularNetwork ?: run {
                    Log.w(TAG, "SYN arrived but no cellular network"); return
                }
                tcpSessions.remove(key)?.close()

                // connect() is off the packet-loop thread — a 100-500 ms connect
                // would stall every other SYN if called here directly.
                val si = srcIp; val sp = srcPort
                val di = dstIp; val dp = dstPort; val s = seq
                pool.submit {
                    try {
                        val sock = Socket()
                        protect(sock)           // no VPN loop
                        net.bindSocket(sock)    // force cellular
                        sock.connect(InetSocketAddress(Packet.intToAddr(di), dp), 10_000)
                        val session = TcpSession(sock, si, sp, di, dp, s, out)
                        tcpSessions[key] = session
                        session.sendSynAck()
                        session.readFromRemote()    // blocks until done
                    } catch (e: Exception) {
                        Log.d(TAG, "TCP connect ${Packet.intToStr(di)}:$dp — ${e.message}")
                        try { Packet.sendRst(si, sp, di, dp, s + 1, out) } catch (_: Exception) {}
                        tcpSessions.remove(key)
                    }
                }
            }

            isRst -> tcpSessions.remove(key)?.close()

            isFin -> {
                // FIX 3: half-close — browser is done sending but may still be
                // reading.  shutdownOutput() lets the server finish its response
                // before the relay tears down.
                val session = tcpSessions.remove(key) ?: return
                if (payLen > 0) session.forwardToRemote(buf, payOff, payLen, seq)
                session.sendFinAck()
                session.halfClose()     // socket.shutdownOutput(); readFromRemote() keeps running
            }

            payLen > 0 -> tcpSessions[key]?.forwardToRemote(buf, payOff, payLen, seq)
        }
    }

    // ── UDP ───────────────────────────────────────────────────────────────────

    private fun handleUdp(
        buf: ByteArray, ipHdr: Int, total: Int,
        srcIp: Int, dstIp: Int, out: FileOutputStream
    ) {
        val srcPort = Packet.readU16(buf, ipHdr)
        val dstPort = Packet.readU16(buf, ipHdr + 2)
        val payOff  = ipHdr + 8
        val payLen  = total - payOff
        if (payLen <= 0) return

        val net = cellularNetwork ?: return
        val key = "$srcIp:$srcPort>$dstIp:$dstPort"

        val session = udpSessions.getOrPut(key) {
            val sock = DatagramSocket()
            protect(sock)
            net.bindSocket(sock)
            sock.soTimeout = 30_000
            UdpSession(sock, srcIp, srcPort, dstIp, dstPort, out).also { s ->
                pool.submit(s::readFromRemote)
            }
        }
        session.send(buf, payOff, payLen)
    }

    // ── TcpSession ────────────────────────────────────────────────────────────

    inner class TcpSession(
        private val socket: Socket,
        private val cliIp: Int, private val cliPort: Int,
        private val srvIp: Int, private val srvPort: Int,
        clientSeq: Long,
        private val tun: FileOutputStream
    ) {
        private val srvSeq = AtomicLong(System.nanoTime() and 0xFFFFFFFFL)
        @Volatile private var cliAck = (clientSeq + 1) and 0xFFFFFFFFL

        fun sendSynAck() {
            writeTun(Packet.tcp(srvIp, cliIp, srvPort, cliPort,
                srvSeq.get(), cliAck, Packet.TCP_SYN_ACK, ByteArray(0)))
            srvSeq.incrementAndGet()
        }

        fun sendFinAck() = try {
            writeTun(Packet.tcp(srvIp, cliIp, srvPort, cliPort,
                srvSeq.get(), cliAck, Packet.TCP_FIN_ACK, ByteArray(0)))
            srvSeq.incrementAndGet()
        } catch (_: Exception) {}

        fun forwardToRemote(buf: ByteArray, offset: Int, length: Int, seq: Long) {
            try {
                socket.getOutputStream().apply { write(buf, offset, length); flush() }
                cliAck = (seq + length) and 0xFFFFFFFFL
                writeTun(Packet.tcp(srvIp, cliIp, srvPort, cliPort,
                    srvSeq.get(), cliAck, Packet.TCP_ACK, ByteArray(0)))
            } catch (_: Exception) { close() }
        }

        /** FIX 1: send at most MSS bytes per TUN write to stay within MTU (1500).
         *  The old 16 KB write caused EMSGSIZE; the catch silently closed the
         *  session, leaving the browser with a FIN and no content. */
        fun readFromRemote() {
            val tmp = ByteArray(MSS)
            try {
                val inp = socket.getInputStream()
                while (!socket.isClosed) {
                    val n = inp.read(tmp)       // reads ≤ MSS bytes
                    if (n < 0) break
                    val payload = tmp.copyOf(n)
                    writeTun(Packet.tcp(srvIp, cliIp, srvPort, cliPort,
                        srvSeq.get(), cliAck, Packet.TCP_PSH_ACK, payload))
                    srvSeq.addAndGet(n.toLong())
                }
            } catch (_: Exception) {}
            sendFinAck()
            close()
        }

        /** FIX 3: half-close — signal to server that the client is done sending,
         *  but keep the socket readable so readFromRemote() can drain any
         *  remaining response data. */
        fun halfClose() = try { socket.shutdownOutput() } catch (_: Exception) {}

        fun close() = try { socket.close() } catch (_: Exception) {}

        private fun writeTun(pkt: ByteArray) = synchronized(tun) { tun.write(pkt) }
    }

    // ── UdpSession ────────────────────────────────────────────────────────────

    inner class UdpSession(
        private val socket: DatagramSocket,
        private val cliIp: Int, private val cliPort: Int,
        private val srvIp: Int, private val srvPort: Int,
        private val tun: FileOutputStream
    ) {
        fun send(buf: ByteArray, offset: Int, length: Int) {
            val data = buf.copyOfRange(offset, offset + length)
            socket.send(DatagramPacket(data, data.size, Packet.intToAddr(srvIp), srvPort))
        }

        fun readFromRemote() {
            val tmp = ByteArray(65535)
            val dp  = DatagramPacket(tmp, tmp.size)
            try {
                while (!socket.isClosed) {
                    socket.receive(dp)
                    val pkt = Packet.udp(srvIp, cliIp, srvPort, cliPort,
                        dp.data.copyOf(dp.length))
                    synchronized(tun) { tun.write(pkt) }
                }
            } catch (_: Exception) {}
        }

        fun close() = try { socket.close() } catch (_: Exception) {}
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Rove VPN", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification() = Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("Rove Dual Network")
        .setContentText("Cellular internet active while connected to dashcam WiFi")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setOngoing(true)
        .build()
}
