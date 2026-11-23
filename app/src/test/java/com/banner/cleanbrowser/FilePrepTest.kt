package com.banner.cleanbrowser

import com.banner.cleanbrowser.download.FilePrep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FilePrepTest {
    private fun tmp() = File.createTempFile("prep", ".bin").also { it.delete() }

    @Test fun growsInSteps() {
        val f = tmp()
        val seen = ArrayList<Long>()
        val size = FilePrep.STEP * 2 + 123
        assertTrue(FilePrep.grow(f, size, { false }) { seen += it })
        assertEquals(size, f.length())
        assertEquals(listOf(FilePrep.STEP, FilePrep.STEP * 2, size), seen)
        f.delete()
    }

    @Test fun stopsWhenCancelled() {
        val f = tmp()
        var calls = 0
        assertFalse(FilePrep.grow(f, FilePrep.STEP * 4, { calls++ >= 2 }) {})
        assertEquals(FilePrep.STEP, f.length())
        f.delete()
    }

    @Test fun leavesFullFilesAlone() {
        val f = tmp()
        f.writeBytes(ByteArray(10))
        assertTrue(FilePrep.grow(f, 10, { error("not asked") }) { error("no step") })
        assertTrue(FilePrep.grow(tmp(), 0, { error("not asked") }) { error("no step") })
        assertEquals(10, f.length())
        f.delete()
    }
}
