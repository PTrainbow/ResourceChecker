package com.ptrain.android.resourcechecker

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * UnzipUtils class extracts files and sub-directories of a standard zip file to
 * a destination directory.
 *
 */
object UnzipUtils {
    private const val ESC = '\u001B'
    private const val CSI_RESET = "$ESC[0m"
    private const val CSI_RED = "$ESC[31m"

    /**
     * @param zipFilePath
     * @param destDirectory
     * @throws IOException
     */
    @Throws(IOException::class)
    fun unzip(zipFilePath: File, destDirectory: String) {
        File(destDirectory).run {
            if (!exists()) {
                mkdirs()
            }
        }
        ZipFile(zipFilePath).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                zip.getInputStream(entry).use { input ->
                    val filePath = destDirectory + File.separator + entry.name
                    if (!entry.isDirectory) {
                        // if the entry is a file, extracts it
                        extractFile(input, filePath)
                    } else {
                        // if the entry is a directory, make the directory
                        val dir = File(filePath)
                        dir.mkdirs()
                    }
                }
            }
        }
    }

    /**
     * Extracts a zip entry (file entry)
     * @param inputStream
     * @param destFilePath
     * @throws IOException
     */
    @Throws(IOException::class)
    private fun extractFile(inputStream: InputStream, destFilePath: String) {
        if (!File(destFilePath).parentFile.exists()) {
            File(destFilePath).parentFile.mkdirs()
        }
        val bos = BufferedOutputStream(FileOutputStream(destFilePath))
        val bytesIn = ByteArray(BUFFER_SIZE)
        var read: Int
        while (inputStream.read(bytesIn).also { read = it } != -1) {
            bos.write(bytesIn, 0, read)
        }
        bos.close()
    }

    /**
     * Size of the buffer to read/write data
     */
    private const val BUFFER_SIZE = 4096


    /**
     * copy from APKMonitor
     */
    fun ZipOutputStream.zip(srcRootDir: String, file: File, storedFileNames: Set<String>) {
        if (file.isFile) {
            var subPath = file.absolutePath
            val index = subPath.indexOf(srcRootDir)
            if (index != -1) {
                subPath = subPath.substring(srcRootDir.length + File.separator.length)
            }
            val entry = ZipEntry(subPath)
            if (storedFileNames.contains(file.name)) {
                entry.let {
                    it.method = ZipEntry.STORED
                    it.compressedSize = file.length()
                    it.size = file.length()
                    it.crc = CRC32().apply {
                        update(file.readBytes())
                    }.value
                }

            }
            putNextEntry(entry)
            FileInputStream(file).use {
                it.copyTo(this)
            }
            closeEntry()
        } else {
            val childFileList = file.listFiles()
            for (n in childFileList.indices) {
                childFileList[n].absolutePath.indexOf(file.absolutePath)
                zip(srcRootDir, childFileList[n], storedFileNames)
            }
        }
    }

    fun File.md5(): String {
        val md = MessageDigest.getInstance("MD5")
        return this.inputStream().use { fis ->
            val buffer = ByteArray(8192)
            generateSequence {
                when (val bytesRead = fis.read(buffer)) {
                    -1 -> null
                    else -> bytesRead
                }
            }.forEach { bytesRead -> md.update(buffer, 0, bytesRead) }
            md.digest().joinToString("") { "%02x".format(it) }
        }
    }

    fun red(s: Any) = "${CSI_RED}${s}${CSI_RESET}"

}