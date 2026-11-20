package com.autodate.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Files on the phone:
 *   captures/<session>/   inspect-mode dumps: 0001_auto.json, 0001.jpg, clicks.jsonl, notes.txt
 *   profiles/<key>/       downscaled screenshots of labeled profiles (for evals), kept 30 days
 */
class CaptureStore private constructor(private val dir: File) {

    private val counter = AtomicInteger(0)

    fun nextIndex(): Int = counter.incrementAndGet()

    fun writeDump(index: Int, reason: String, doc: JSONObject) {
        File(dir, "%04d_%s.json".format(index, reason)).writeText(doc.toString(1))
    }

    fun writeShot(index: Int, bitmap: Bitmap) {
        File(dir, "%04d.jpg".format(index)).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    fun note(index: Int, message: String) = append("notes.txt", "%04d %s\n".format(index, message))

    fun appendClick(click: JSONObject) = append("clicks.jsonl", click.toString() + "\n")

    @Synchronized
    private fun append(name: String, line: String) = File(dir, name).appendText(line)

    companion object {
        private const val PROFILE_RETENTION_DAYS = 30L

        private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        private fun base(context: Context) = context.getExternalFilesDir(null) ?: context.filesDir

        fun capturesRoot(context: Context) = File(base(context), "captures")

        private fun profilesRoot(context: Context) = File(base(context), "profiles")

        fun newSession(context: Context): CaptureStore =
            CaptureStore(File(capturesRoot(context), stamp()).apply { mkdirs() })

        fun saveProfileImages(context: Context, key: String, jpegs: List<ByteArray>) {
            val d = File(profilesRoot(context), key).apply { mkdirs() }
            jpegs.forEachIndexed { i, bytes -> File(d, "$i.jpg").writeBytes(bytes) }
        }

        /** Saved screenshots for one profile, top to bottom. Empty if they were pruned or deleted. */
        fun profileImages(context: Context, key: String): List<File> =
            File(profilesRoot(context), key).listFiles { f -> f.extension == "jpg" }
                ?.sortedBy { it.nameWithoutExtension.toIntOrNull() ?: Int.MAX_VALUE }
                .orEmpty()

        fun pruneOldProfiles(context: Context) {
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(PROFILE_RETENTION_DAYS)
            profilesRoot(context).listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
        }

        /** (file count, total bytes) of everything stored. */
        fun stats(context: Context): Pair<Int, Long> {
            val files = listOf(capturesRoot(context), profilesRoot(context)).flatMap { r -> r.walkTopDown().filter { it.isFile }.toList() }
            return files.size to files.sumOf { it.length() }
        }

        fun deleteAll(context: Context) {
            capturesRoot(context).deleteRecursively()
            profilesRoot(context).deleteRecursively()
        }

        /** Zips captures, labeled-profile images and the database into Download/autoDate/. Returns the path. */
        fun exportZip(context: Context, db: JSONObject): String {
            val name = "autodate_${stamp()}.zip"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/autoDate")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create $name in Downloads")
            val out = resolver.openOutputStream(uri) ?: throw IOException("Could not open $name")
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("db.json"))
                zip.write(db.toString(1).toByteArray())
                zip.closeEntry()
                val root = base(context)
                listOf(capturesRoot(context), profilesRoot(context)).forEach { dir ->
                    dir.walkTopDown().filter { it.isFile }.forEach { f ->
                        zip.putNextEntry(ZipEntry(f.relativeTo(root).path))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            return "Download/autoDate/$name"
        }
    }
}
