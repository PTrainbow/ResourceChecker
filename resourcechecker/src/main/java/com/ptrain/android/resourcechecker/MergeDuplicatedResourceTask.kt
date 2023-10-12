package com.ptrain.android.resourcechecker

import com.android.build.gradle.api.ApplicationVariant
import com.android.build.gradle.internal.tasks.OptimizeResourcesTask
import com.ptrain.android.resourcechecker.UnzipUtils.zip
import org.apache.commons.io.FileUtils
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.Task
import pink.madis.apk.arsc.ResourceFile
import pink.madis.apk.arsc.ResourceTableChunk
import pink.madis.apk.arsc.StringPoolChunk
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileWriter
import java.text.CharacterIterator
import java.text.StringCharacterIterator
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 部分借鉴自：APKMonitor
 */
class MergeDuplicatedResourceTask {

    companion object {
        private const val ARSC = "resources.arsc"
        const val REPEAT_MAPPING_TEXT_FILE_NAME = "duplicated-resources.txt"
    }

    fun run(project: Project, applicationVariant: ApplicationVariant) {
        val variantName = applicationVariant.name.capitalize()
        // 高版本 agp task 为 optimizeResources
        val processResource = project.tasks.getByName("optimize${variantName}Resources")
        processResource.doLast(object : Action<Task> {
            override fun execute(task: Task) {
                // 主逻辑
                val resourcesTask = task as OptimizeResourcesTask
                val resPackageOutputFolder = resourcesTask.optimizedProcessedRes
                // 寻找 .ap_ 文件
                resPackageOutputFolder.asFileTree.files
                    .filter {
                        it.name.endsWith(".ap_")
                    }.forEach { file ->
                        parseApZip(file, project)
                    }
            }
        })
    }

    /**
     * 解压 .ap_ 合并 .arsc 中冗余项目
     */
    private fun parseApZip(apZip: File, project: Project) {
        // 解压到上上层目录
        val destZipDir = apZip.nameWithoutExtension
        val unZipDir = File(File(apZip.parent).parent, destZipDir)
        if (unZipDir.exists()) {
            FileUtils.deleteDirectory(unZipDir)
        }
        UnzipUtils.unzip(apZip, unZipDir.absolutePath)
        val arscFile = File(unZipDir, ARSC)
        FileInputStream(arscFile).use { input ->
            val arscFileContent = ResourceFile.fromInputStream(input)
            // 核心逻辑
            mergeDuplicatedResource(project, apZip, unZipDir.absolutePath, arscFileContent)
            // 重新压缩 .ap_
            createNewArscAndApZip(arscFile, arscFileContent, apZip, unZipDir)
        }
    }

    /**
     * 合并重复资源，修改 arsc 文件
     */
    private fun mergeDuplicatedResource(
        project: Project,
        apZip: File,
        unZipDirPath: String,
        arscFileStream: ResourceFile
    ) {
        val duplicatedMap = hashMapOf<String, MutableList<ZipEntry>>()
        // 存储所有的 zip entry crc 校验码，相同的合并
        ZipFile(apZip).entries()
            .iterator()
            .forEach {
                val file = File(it.name)
                val key = "${file.parent}#${it.crc}"
                val list = duplicatedMap.getOrDefault(key, mutableListOf())
                list.add(it)
                duplicatedMap[key] = list
            }
        val fileWriter =
            FileWriter("${project.buildDir}${File.separator}${REPEAT_MAPPING_TEXT_FILE_NAME}")

        var deleteRepeatNumbers = 0L
        var deleteRepeatFileSize = 0L
        val repeatSizeMap = hashMapOf<String, Int>()
        // 遍历重复项，删除重复资源，输出结果文件
        duplicatedMap.filter { (_, duplicatedEntry) ->
            duplicatedEntry.size >= 2
        }.forEach { (_, duplicatedEntry) ->
            val firstZipEntry = duplicatedEntry[0]
            val duplicatedEntries = duplicatedEntry.subList(1, duplicatedEntry.size)
            repeatSizeMap[firstZipEntry.name] = duplicatedEntry.size
            fileWriter.write("${firstZipEntry.name} <--- ${firstZipEntry.name}\n ")
            deleteRepeatNumbers += duplicatedEntries.size
            duplicatedEntries.forEach { zipEntry ->
                fileWriter.write("${getSpaceByLength(firstZipEntry.name.length)}<--- ${zipEntry.name}\n")
                // 删除重复资源
                File("${unZipDirPath}${File.separator}${zipEntry.name}").delete()
                deleteRepeatFileSize += zipEntry.size
                // 修改 arsc 文件指向
                arscFileStream
                    .chunks
                    .asSequence()
                    .filterIsInstance<ResourceTableChunk>()
                    .forEach {
                        val index = it.stringPool.indexOf(zipEntry.name)
                        if (index != -1) {
                            it.stringPool.setString(index, firstZipEntry.name)
                        }
                    }
            }
            fileWriter.write("----------------------------------------------\n")
        }
        fileWriter.write("removed count:${deleteRepeatNumbers}\n")
        fileWriter.write("removed size:${humanReadableByteCountBin(deleteRepeatFileSize)}\n")
        fileWriter.close()
    }

    /**
     * 生成新的 arsc 和 ap_ 并 删除旧的
     */
    private fun createNewArscAndApZip(
        arscFile: File,
        arscFileContent: ResourceFile,
        apZip: File,
        unZipDir: File
    ) {
        // 删除原 arsc
        arscFile.delete()
        // 生成新 arsc
        FileOutputStream(arscFile).use {
            it.write(arscFileContent.toByteArray())
        }
        // 压缩成 .ap_
        ZipOutputStream(apZip.outputStream()).use {
            it.zip(unZipDir.absolutePath, unZipDir, setOf(ARSC))
        }
        // 删除解压资源
        FileUtils.deleteDirectory(unZipDir)
    }


    private fun humanReadableByteCountBin(bytes: Long): String? {
        val absB = if (bytes == Long.MIN_VALUE) Long.MAX_VALUE else Math.abs(bytes)
        if (absB < 1024) {
            return "$bytes B"
        }
        var value = absB
        val ci: CharacterIterator = StringCharacterIterator("KMGTPE")
        var i = 40
        while (i >= 0 && absB > 0xfffccccccccccccL shr i) {
            value = value shr 10
            ci.next()
            i -= 10
        }
        value *= java.lang.Long.signum(bytes).toLong()
        return String.format("%.1f %ciB", value / 1024.0, ci.current())
    }


    fun StringPoolChunk.setString(index: Int, value: String) {
        try {
            val field = javaClass.getDeclaredField("strings")
            field.isAccessible = true
            val list = field.get(this) as MutableList<String>
            list[index] = value
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }

    private val spaceCacheMap by lazy { mutableMapOf<Int, String>() }


    private fun getSpaceByLength(length: Int): String {
        return spaceCacheMap.getOrPut(length) {
            (" ").repeat(length)
        }
    }

}