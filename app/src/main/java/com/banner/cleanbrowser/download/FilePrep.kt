package com.banner.cleanbrowser.download

import android.system.Os
import java.io.File
import java.io.FileDescriptor
import java.io.RandomAccessFile

/**
 * Grows files to their final size in small steps on storage without sparse files (exFAT/FAT SD
 * cards). There, writing at an offset past the end of a file, or setting its size, makes the
 * kernel write zeros up to that point inside one call, and the app process cannot be stopped
 * until it returns (76 GB took about 30 minutes). Growing in steps keeps every call short.
 */
object FilePrep {
    /** About 1.5 s of zero-filling on the Pocket FIT's SD card. */
    const val STEP = 64L shl 20

    /** Grows [file] to [size]. Returns false when [cancelled] stopped it. */
    fun grow(file: File, size: Long, cancelled: () -> Boolean, progress: (Long) -> Unit): Boolean {
        if (file.length() >= size) return true
        if (cancelled()) return false
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            var len = raf.length()
            while (len < size) {
                if (cancelled()) return false
                len = minOf(len + STEP, size)
                raf.setLength(len)
                progress(len)
            }
        }
        return true
    }

    /** Same for an open file descriptor (also works for SAF documents). */
    fun grow(fd: FileDescriptor, size: Long, cancelled: () -> Boolean, progress: (Long) -> Unit): Boolean {
        var len = Os.fstat(fd).st_size
        while (len < size) {
            if (cancelled()) return false
            len = minOf(len + STEP, size)
            Os.ftruncate(fd, len)
            progress(len)
        }
        return true
    }
}
