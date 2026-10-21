package com.novelgrabber.app

import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipFile

/** Random access to the files inside an epub, whatever it is stored on. */
interface EpubZip : Closeable {
    fun names(): List<String>
    /** The (decompressed) bytes of one entry, or null if there is no such entry. */
    fun open(name: String): InputStream?
}

/** A plain file on disk. */
class FileZip(file: File) : EpubZip {
    private val zip = ZipFile(file)
    override fun names(): List<String> = zip.entries().asSequence().map { it.name }.toList()
    override fun open(name: String): InputStream? = zip.getEntry(name)?.let { zip.getInputStream(it) }
    override fun close() { try { zip.close() } catch (e: Exception) {} }
}

/** A temporary copy that is deleted when closed (only used when a provider can't be read in place). */
class TempFileZip(private val file: File) : EpubZip {
    private val z = FileZip(file)
    override fun names() = z.names()
    override fun open(name: String) = z.open(name)
    override fun close() { z.close(); file.delete() }
}

/**
 * Reads an epub IN PLACE through a file descriptor (Storage Access Framework) — nothing is copied.
 * Only the zip's table of contents is read up front; each entry is then fetched with a positional read
 * when asked for. Zip64 archives (> 4 GB) are not supported (the caller falls back to a temporary copy).
 */
class ChannelZip(pfd: ParcelFileDescriptor) : EpubZip {
    private class Ent(val method: Int, val comp: Long, val off: Long)

    private val input = ParcelFileDescriptor.AutoCloseInputStream(pfd)   // owns the descriptor: closing it closes pfd
    private val ch: FileChannel = input.channel
    private val entries = LinkedHashMap<String, Ent>()

    init { try { readDirectory() } catch (e: Throwable) { close(); throw e } }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int): Long = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)

    private fun readFully(buf: ByteArray, pos: Long) {
        var done = 0
        while (done < buf.size) {
            val n = ch.read(ByteBuffer.wrap(buf, done, buf.size - done), pos + done)
            if (n < 0) throw IOException("unexpected end of file")
            done += n
        }
    }

    private fun readDirectory() {
        val size = ch.size()
        if (size < 22) throw IOException("not a zip file")
        val tailLen = minOf(size, 65557L).toInt()
        val tail = ByteArray(tailLen)
        readFully(tail, size - tailLen)
        var p = tailLen - 22
        while (p >= 0 && !(u32(tail, p) == 0x06054b50L)) p--
        if (p < 0) throw IOException("no zip directory found")
        val total = u16(tail, p + 10)
        val cdSize = u32(tail, p + 12)
        val cdOff = u32(tail, p + 16)
        if (total == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOff == 0xFFFFFFFFL) throw IOException("zip64 not supported")
        val cd = ByteArray(cdSize.toInt())
        readFully(cd, cdOff)
        var q = 0
        while (q + 46 <= cd.size && u32(cd, q) == 0x02014b50L) {
            val method = u16(cd, q + 10)
            val comp = u32(cd, q + 20)
            val nl = u16(cd, q + 28); val el = u16(cd, q + 30); val cl = u16(cd, q + 32)
            val off = u32(cd, q + 42)
            if (comp == 0xFFFFFFFFL || off == 0xFFFFFFFFL) throw IOException("zip64 not supported")
            entries[String(cd, q + 46, nl, Charsets.UTF_8)] = Ent(method, comp, off)
            q += 46 + nl + el + cl
        }
        if (entries.isEmpty()) throw IOException("empty zip")
    }

    override fun names(): List<String> = entries.keys.toList()

    override fun open(name: String): InputStream? {
        val e = entries[name] ?: return null
        val hdr = ByteArray(30)
        readFully(hdr, e.off)
        if (u32(hdr, 0) != 0x04034b50L) throw IOException("bad zip entry")
        val dataOff = e.off + 30 + u16(hdr, 26) + u16(hdr, 28)
        val comp = ByteArray(e.comp.toInt())
        readFully(comp, dataOff)
        return when (e.method) {
            0 -> ByteArrayInputStream(comp)
            8 -> {
                val inf = Inflater(true)
                object : InflaterInputStream(ByteArrayInputStream(comp), inf) {
                    override fun close() { try { super.close() } finally { inf.end() } }
                }
            }
            else -> throw IOException("unsupported zip compression")
        }
    }

    override fun close() { try { input.close() } catch (e: Exception) {} }
}
