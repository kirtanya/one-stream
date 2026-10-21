package com.kirtanya.reel.homedisk

import com.kirtanya.reel.core.homedisk.DiskLayout
import com.kirtanya.reel.core.homedisk.DiskTest
import com.kirtanya.reel.core.homedisk.DiskTestFacts
import com.kirtanya.reel.core.homedisk.DiskTestFacts.SignIn
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Properties
import kotlin.random.Random
import org.codelibs.jcifs.smb.CIFSContext
import org.codelibs.jcifs.smb.config.PropertyConfiguration
import org.codelibs.jcifs.smb.context.BaseContext
import org.codelibs.jcifs.smb.impl.NtStatus
import org.codelibs.jcifs.smb.impl.NtlmPasswordAuthenticator
import org.codelibs.jcifs.smb.impl.SmbAuthException
import org.codelibs.jcifs.smb.impl.SmbException
import org.codelibs.jcifs.smb.impl.SmbFile
import org.codelibs.jcifs.smb.impl.SmbRandomAccessFile

/** What the Test signs in with. The password only ever goes into [NtlmPasswordAuthenticator], never a URL. */
class SmbLogin(val address: String, val share: String, val user: String, val password: String, val smb1: Boolean)

/**
 * The disk Test (docs/HOME_DISK.md §4.5) with org.codelibs:jcifs 3.0.4: reachable? sign-in, SMB version, free
 * space, an 8 MiB write + read-back with a hash check (MB/s), the 4 GiB big-file check (FAT32 fails it),
 * rename with replace, delete. Blocking: run it off the main thread. Callers check home first (I6).
 */
object SmbTest {
    private const val CONNECT_MS = 3_000

    /** Something listens on the SMB ports: no credentials are sent for this. */
    fun reachable(address: String): Boolean = listOf(445, 139).any { port ->
        runCatching { Socket().use { it.connect(InetSocketAddress(address, port), CONNECT_MS) } }.isSuccess
    }

    fun context(login: SmbLogin): CIFSContext {
        // §4.3: SMB 2.0.2–3.1.1, SMB1 only for an SMB1-only router; no signing / encryption unless the server demands it.
        val p = Properties().apply {
            setProperty("jcifs.client.minVersion", if (login.smb1) "SMB1" else "SMB202")
            setProperty("jcifs.client.maxVersion", "SMB311")
            setProperty("jcifs.client.disableSMB1", (!login.smb1).toString())
            setProperty("jcifs.client.signingPreferred", "false")
            setProperty("jcifs.client.signingEnforced", "false")
            setProperty("jcifs.client.encryptionEnabled", "false")
            setProperty("jcifs.client.maxTransferSize", "1048576")
            setProperty("jcifs.client.connTimeout", "5000")
            setProperty("jcifs.client.responseTimeout", "30000")
            setProperty("jcifs.client.soTimeout", "35000")
            setProperty("jcifs.client.dfs.disabled", "true")
            setProperty("jcifs.resolveOrder", "DNS")
            setProperty("jcifs.lmCompatibility", "3")
        }
        return BaseContext(PropertyConfiguration(p)).withCredentials(NtlmPasswordAuthenticator("", login.user, login.password))
    }

    fun run(login: SmbLogin): DiskTestFacts {
        val base = DiskTestFacts(login.address, login.share, reachable = false)
        if (!reachable(login.address)) return base
        val first = signIn(login)
        // An SMB1-only router refuses SMB2+ negotiation: try once more in SMB1 mode (then the line says so).
        val (ctx, facts) = if (first.second.signIn == SignIn.FAILED && !login.smb1) {
            val retry = signIn(SmbLogin(login.address, login.share, login.user, login.password, smb1 = true))
            if (retry.second.signIn == SignIn.OK) retry else first
        } else first
        if (facts.signIn != SignIn.OK || ctx == null) return facts
        return try {
            measure(ctx, login, facts)
        } catch (e: Exception) {
            facts.copy(error = plain(e))
        } finally {
            runCatching { ctx.close() }
        }
    }

    /** The health check: reachable, sign in, free space; nothing written. */
    fun signInOnly(login: SmbLogin): DiskTestFacts {
        if (!reachable(login.address)) return DiskTestFacts(login.address, login.share, reachable = false)
        val (ctx, facts) = signIn(login)
        runCatching { ctx?.close() }
        return facts
    }

    private fun signIn(login: SmbLogin): Pair<CIFSContext?, DiskTestFacts> {
        val facts = DiskTestFacts(login.address, login.share, reachable = true, smb1 = login.smb1)
        val ctx = context(login)
        return try {
            val root = SmbFile(shareUrl(login), ctx)
            root.use {
                it.exists()
                val protocol = protocolOf(it)
                ctx to facts.copy(signIn = SignIn.OK, protocol = protocol, freeBytes = runCatching { it.diskFreeSpace }.getOrNull())
            }
        } catch (e: SmbAuthException) {
            runCatching { ctx.close() }
            null to facts.copy(signIn = SignIn.PASSWORD_REJECTED)
        } catch (e: SmbException) {
            runCatching { ctx.close() }
            val status = e.ntStatus
            when (status) {
                NtStatus.NT_STATUS_LOGON_FAILURE, NtStatus.NT_STATUS_WRONG_PASSWORD, NtStatus.NT_STATUS_ACCOUNT_LOCKED_OUT ->
                    null to facts.copy(signIn = SignIn.PASSWORD_REJECTED)
                NtStatus.NT_STATUS_BAD_NETWORK_NAME -> null to facts.copy(signIn = SignIn.NO_SHARE)
                else -> null to facts.copy(signIn = SignIn.FAILED, error = plain(e))
            }
        } catch (e: Exception) {
            runCatching { ctx.close() }
            null to facts.copy(signIn = SignIn.FAILED, error = plain(e))
        }
    }

    private fun measure(ctx: CIFSContext, login: SmbLogin, signedIn: DiskTestFacts): DiskTestFacts {
        val share = shareUrl(login)
        SmbFile(share + DiskLayout.ROOT + "/" + DiskLayout.META + "/", ctx).use { if (!it.exists()) it.mkdirs() }
        val data = Random.nextBytes(DiskTest.TEST_BYTES.toInt())
        val sha = MessageDigest.getInstance("SHA-256").digest(data)
        val test = SmbFile(share + DiskLayout.testFile, ctx)
        val t0 = System.nanoTime()
        test.openOutputStream(false).use { out ->
            var off = 0
            while (off < data.size) {
                val n = minOf(CHUNK, data.size - off)
                out.write(data, off, n)
                off += n
            }
            out.flush()
        }
        val writeSec = (System.nanoTime() - t0) / 1e9
        val back = ByteArray(data.size)
        val t1 = System.nanoTime()
        test.openInputStream().use { input ->
            var off = 0
            while (off < back.size) {
                val n = input.read(back, off, minOf(CHUNK, back.size - off))
                if (n < 0) break
                off += n
            }
        }
        val readSec = (System.nanoTime() - t1) / 1e9
        val hashOk = MessageDigest.getInstance("SHA-256").digest(back).contentEquals(sha)

        // A file past 4 GiB: FAT32 can't hold one, and 4K films would fail later (§4.5).
        val big = SmbFile(share + DiskLayout.ROOT + "/" + DiskLayout.META + "/big.bin", ctx)
        val bigOk = runCatching {
            SmbRandomAccessFile(big, "rw").use { raf ->
                raf.setLength(DiskTest.FAT32_LIMIT + 2)
                raf.seek(DiskTest.FAT32_LIMIT + 1)
                raf.write(1)
            }
            true
        }.getOrDefault(false)
        runCatching { big.delete() }
        // Rename with replace (the piece-map write rule needs it), then clean up.
        runCatching {
            val moved = SmbFile(share + DiskLayout.ROOT + "/" + DiskLayout.META + "/test2.bin", ctx)
            test.renameTo(moved, true)
            moved.delete()
        }.onFailure { runCatching { test.delete() } }

        val mb = DiskTest.TEST_BYTES / 1_000_000.0
        return signedIn.copy(
            writeMBps = mb / writeSec.coerceAtLeast(1e-3),
            readMBps = mb / readSec.coerceAtLeast(1e-3),
            hashOk = hashOk,
            bigFiles = bigOk,
        )
    }

    /**
     * "SMB 3.1.1" from the negotiated dialect. jcifs keeps it behind package-private classes, so it is read by
     * reflection (names kept by proguard-rules.pro); anything unexpected falls back to "SMB 2+" / "SMB1".
     */
    private fun protocolOf(f: SmbFile): String = runCatching {
        f.treeHandle.use { tree ->
            val dialect = call(call(call(tree, "getSession"), "getTransport"), "getNegotiateResponse")
                .let { call(it, "getSelectedDialect") } as org.codelibs.jcifs.smb.DialectVersion
            when (dialect) {
                org.codelibs.jcifs.smb.DialectVersion.SMB1 -> "SMB1"
                org.codelibs.jcifs.smb.DialectVersion.SMB202 -> "SMB 2.0.2"
                org.codelibs.jcifs.smb.DialectVersion.SMB210 -> "SMB 2.1"
                org.codelibs.jcifs.smb.DialectVersion.SMB300 -> "SMB 3.0"
                org.codelibs.jcifs.smb.DialectVersion.SMB302 -> "SMB 3.0.2"
                org.codelibs.jcifs.smb.DialectVersion.SMB311 -> "SMB 3.1.1"
                else -> "SMB"
            }
        }
    }.getOrElse { runCatching { f.treeHandle.use { if (it.isSMB2) "SMB 2+" else "SMB1" } }.getOrDefault("SMB") }

    private fun call(target: Any?, name: String): Any? {
        val o = target ?: return null
        val m = generateSequence<Class<*>>(o.javaClass) { it.superclass }
            .firstNotNullOfOrNull { c -> runCatching { c.getDeclaredMethod(name) }.getOrNull() } ?: return null
        m.isAccessible = true
        return m.invoke(o)
    }

    private fun shareUrl(login: SmbLogin) = "smb://${login.address.trim()}/${login.share.trim().trim('/')}/"

    /** An error for the result line: the exception's own words, never more than a line (no paths beyond the share). */
    private fun plain(e: Throwable): String = (e.message ?: e.javaClass.simpleName).lineSequence().first().take(120)

    private const val CHUNK = 1024 * 1024
}
