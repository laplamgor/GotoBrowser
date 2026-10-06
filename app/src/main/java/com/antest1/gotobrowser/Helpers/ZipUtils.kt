package com.antest1.gotobrowser.Helpers

import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

object ZipUtils {

    /** Extracts [zipFile] into [destDir], creating directories as needed. */
    @JvmStatic
    @Throws(IOException::class)
    fun extract(zipFile: File, destDir: File) {
        destDir.mkdirs()
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            generateSequence { zis.nextEntry }.forEach { entry ->
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out -> zis.copyTo(out) }
                }
                zis.closeEntry()
            }
        }
    }
}
