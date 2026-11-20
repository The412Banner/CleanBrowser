package com.banner.cleanbrowser.download

import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import java.io.File

object Storage {
    /**
     * Pre-allocation is only safe on the phone's internal storage. SD cards are usually exFAT/FAT,
     * where reserving space means writing zeros through the whole file (hours for big files, and the
     * app process cannot be stopped meanwhile). Those folders always use sparse files.
     */
    fun preallocOk(folder: String): Boolean {
        if (folder.startsWith("content://")) {
            val id = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(folder)) }.getOrNull() ?: return false
            return id.startsWith("primary:")
        }
        val f = File(folder)
        val primary = Environment.getExternalStorageDirectory().absolutePath
        if (f.absolutePath.startsWith(primary)) return true
        if (f.absolutePath.startsWith("/data/")) return true
        return runCatching { !Environment.isExternalStorageRemovable(f) && Environment.isExternalStorageEmulated(f) }.getOrDefault(false)
    }

    fun free(folder: String): Long =
        if (folder.startsWith("content://")) -1L else runCatching { StatFs(folder).availableBytes }.getOrDefault(-1L)
}
