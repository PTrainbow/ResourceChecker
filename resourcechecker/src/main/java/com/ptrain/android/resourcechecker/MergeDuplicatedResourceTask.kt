package com.ptrain.android.resourcechecker

import com.android.build.gradle.api.ApplicationVariant
import com.android.build.gradle.internal.res.LinkApplicationAndroidResourcesTask
import com.android.build.gradle.internal.tasks.OptimizeResourcesTask
import com.ptrain.android.resourcechecker.UnzipUtils.md5
import com.ptrain.android.resourcechecker.UnzipUtils.red
import com.ptrain.android.resourcechecker.UnzipUtils.zip
import org.apache.commons.io.FileUtils
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.UnknownTaskException
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
        private const val TASK_NAME = "MergeDuplicatedResourceTask"
        private const val ARSC = "resources.arsc"
        const val REPEAT_MAPPING_TEXT_FILE_NAME = "duplicated.txt"
    }

    fun run(project: Project, applicationVariant: ApplicationVariant) {
        val variantName = applicationVariant.name.capitalize()
        // 高版本 agp task 默认为 optimizeResources，但是可能也会被强制关闭
        var processResource: Task? = null
        try {
            processResource = project.tasks.getByName("optimize${variantName}Resources")
        } catch (e: UnknownTaskException) {
            processResource = project.tasks.getByName("process${variantName}Resources")
        }
        processResource?.doLast(object : Action<Task> {
            override fun execute(task: Task) {

                // 主逻辑
                val resPackageOutputFolder = when (task) {
                    is OptimizeResourcesTask -> {
                        task.optimizedProcessedRes
                    }

                    is LinkApplicationAndroidResourcesTask -> {
                        task.resPackageOutputFolder
                    }

                    else -> {
                        null
                    }
                }
                // 寻找 .ap_ 文件
                resPackageOutputFolder?.let { directory ->
                    directory.asFileTree.files
                        .filter {
                            it.name.endsWith(".ap_")
                        }.forEach { file ->
                            val startTs = System.currentTimeMillis()
                            println("$TASK_NAME: start merging ${file.nameWithoutExtension}")
                            parseApZip(file, project)
                            println("$TASK_NAME: merge ${file.nameWithoutExtension} done! cost ${System.currentTimeMillis() - startTs}ms")
                        }
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
                if (it.size == 0L) {
                    return@forEach
                }
                val file = File(it.name)
                val key = "${it.crc}#${it.size}#${file.extension}"
                val list = duplicatedMap.getOrDefault(key, mutableListOf())
                list.add(it)
                duplicatedMap[key] = list
            }
        val fileWriter =
            FileWriter("${project.buildDir}${File.separator}${apZip.nameWithoutExtension}-${REPEAT_MAPPING_TEXT_FILE_NAME}")

        var deleteRepeatNumbers = 0L
        var deleteRepeatFileSize = 0L
        val repeatSizeMap = hashMapOf<String, Int>()
        val repeatMd5Map = hashMapOf<String, String>()

        // 读取 arsc
        val arscChunks = arscFileStream.chunks
            .filterIsInstance<ResourceTableChunk>()

        // 遍历重复项，删除重复资源，输出结果文件
        duplicatedMap.filter { (_, duplicatedEntry) ->
            duplicatedEntry.size >= 2
        }.forEach { (_, duplicatedEntry) ->
            val firstZipEntry = duplicatedEntry[0]
            val duplicatedEntries = duplicatedEntry.subList(1, duplicatedEntry.size)
            val firstFile = File("${unZipDirPath}${File.separator}${firstZipEntry.name}")
            if (!firstFile.exists()) {
                println(red("file ${firstZipEntry.name} not exists! Is your file system case sensitive?"))
                return@forEach
            }
            // crc 相同的，再次计算一遍 md5 兜底
            repeatMd5Map[firstZipEntry.name] = firstFile.md5()
            repeatSizeMap[firstZipEntry.name] = duplicatedEntry.size
            fileWriter.write("${firstZipEntry.name} <--- ${firstZipEntry.name}\n ")
            duplicatedEntries.forEach { zipEntry ->
                val duplicatedFile = File("${unZipDirPath}${File.separator}${zipEntry.name}")
                if (!duplicatedFile.exists()) {
                    return@forEach
                }
                val tmpMd5 = duplicatedFile.md5()
                if (repeatMd5Map[firstZipEntry.name] != tmpMd5) {
                    println(red("these two files ${firstZipEntry.name} ${zipEntry.name} have the same crc, but their md5 is not the same! Is your file system case sensitive?"))
                    return@forEach
                }
                fileWriter.write("${getSpaceByLength(firstZipEntry.name.length)} <--- ${zipEntry.name}\n")
                // 删除重复资源
                duplicatedFile.delete()
                deleteRepeatFileSize += zipEntry.size
                deleteRepeatNumbers++
                // 遍历修改
                arscChunks.forEach {
                    it.stringPool.setString(zipEntry.name, firstZipEntry.name)
                }
            }
            fileWriter.write("----------------------------------------------\n")
        }
        fileWriter.write("removed count:${deleteRepeatNumbers}\n")
        val humanRead = humanReadableByteCountBin(deleteRepeatFileSize)
        fileWriter.write("removed size:$humanRead\n")
        fileWriter.close()
        println("$TASK_NAME: removed count = $deleteRepeatNumbers")
        println("$TASK_NAME: removed size = $humanRead")

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


    fun StringPoolChunk.setString(old: String, new: String) {
        try {
            val field = javaClass.getDeclaredField("strings")
            field.isAccessible = true
            val list = field.get(this) as MutableList<String>
            // 需要替换所有
            list.replaceAll {
                if (it == old) {
                    new
                } else {
                    it
                }
            }
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