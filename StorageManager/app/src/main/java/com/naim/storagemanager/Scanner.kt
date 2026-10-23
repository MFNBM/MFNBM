package com.naim.storagemanager

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Process
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

enum class Category(val label: String) {
    APK("APK files"),
    JUNK("Junk & temp files"),
    DUPLICATE("Duplicate files"),
    LARGE("Large files"),
    EMPTY("Empty folders")
}

data class Item(
    val path: String,
    val size: Long,
    val category: Category,
    val detail: String
)

data class AppEntry(
    val pkg: String,
    val label: String,
    val size: Long,
    val exact: Boolean,
    val system: Boolean,
    val version: String
)

object Scanner {
    private val junkExt = setOf(
        "tmp", "temp", "log", "bak", "old", "chk", "dmp", "cache", "crdownload", "part", "thumb"
    )
    private val junkDirMarkers = listOf("/.thumbnails/", "/.trash/", "/.trashed", "/lost.dir/")
    private val skipPrefixes = listOf("/Android/data", "/Android/obb")

    fun hasAllFilesAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    fun scan(ctx: Context, largeMb: Int, onProgress: (String) -> Unit): List<Item> {
        val root = Environment.getExternalStorageDirectory()
        val rootPath = root.absolutePath
        val found = LinkedHashMap<String, Item>()
        val bySize = HashMap<Long, MutableList<File>>()
        val largeBytes = largeMb * 1024L * 1024L
        var count = 0

        val walk = root.walkTopDown().onEnter { dir ->
            val rel = dir.absolutePath.removePrefix(rootPath)
            skipPrefixes.none { rel.startsWith(it) }
        }

        for (f in walk) {
            count++
            if (count % 200 == 0) onProgress("Scanned $count items…  ${f.parentFile?.name ?: ""}")
            val rel = f.absolutePath.removePrefix(rootPath)

            if (f.isDirectory) {
                if (f != root) {
                    val names = f.list()
                    if (names != null && names.isEmpty()) {
                        found[f.absolutePath] = Item(f.absolutePath, 0L, Category.EMPTY, "Empty folder")
                    }
                }
                continue
            }

            val size = f.length()
            val ext = f.extension.lowercase()
            val lowerRel = rel.lowercase() + "/"

            when {
                ext == "apk" || ext == "apks" || ext == "xapk" || ext == "apkm" ->
                    found[f.absolutePath] = Item(f.absolutePath, size, Category.APK, describeApk(ctx, f, ext))

                ext in junkExt || f.name.endsWith("~") || junkDirMarkers.any { lowerRel.contains(it) } ->
                    found[f.absolutePath] = Item(f.absolutePath, size, Category.JUNK, "Temp/cache/log file (.$ext)")

                size >= largeBytes ->
                    found[f.absolutePath] = Item(f.absolutePath, size, Category.LARGE, "Large file")
            }

            if (size >= 100 * 1024 && ext != "apk") {
                bySize.getOrPut(size) { mutableListOf() }.add(f)
            }
        }

        onProgress("Checking duplicates…")
        for ((size, files) in bySize) {
            if (files.size < 2) continue
            val byHash = HashMap<String, MutableList<File>>()
            for (f in files) {
                val h = hash(f) ?: continue
                byHash.getOrPut(h) { mutableListOf() }.add(f)
            }
            for (group in byHash.values) {
                if (group.size < 2) continue
                val sorted = group.sortedBy { it.lastModified() }
                val keeper = sorted.first()
                for (dup in sorted.drop(1)) {
                    val existing = found[dup.absolutePath]
                    if (existing == null || existing.category == Category.LARGE) {
                        found[dup.absolutePath] = Item(
                            dup.absolutePath, size, Category.DUPLICATE,
                            "Same content as ${keeper.name}"
                        )
                    }
                }
            }
        }

        onProgress("Done")
        return found.values.sortedByDescending { it.size }
    }

    private fun hash(f: File): String? = try {
        val md = MessageDigest.getInstance("MD5")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (t: Throwable) {
        null
    }

    @Suppress("DEPRECATION")
    private fun describeApk(ctx: Context, f: File, ext: String): String {
        if (ext != "apk") return "Split/bundle package (.$ext) – installer leftover"
        val pm = ctx.packageManager
        val info = try {
            if (Build.VERSION.SDK_INT >= 33)
                pm.getPackageArchiveInfo(f.absolutePath, PackageManager.PackageInfoFlags.of(0))
            else pm.getPackageArchiveInfo(f.absolutePath, 0)
        } catch (t: Throwable) {
            null
        } ?: return "Corrupt or incomplete APK"

        val appInfo = info.applicationInfo
        val name = try {
            appInfo?.let {
                it.sourceDir = f.absolutePath
                it.publicSourceDir = f.absolutePath
                pm.getApplicationLabel(it).toString()
            } ?: info.packageName
        } catch (t: Throwable) {
            info.packageName
        }
        val apkVer = PackageInfoCompat.getLongVersionCode(info)
        val installed = try {
            pm.getPackageInfo(info.packageName, 0)
        } catch (t: Throwable) {
            null
        }
        val status = when {
            installed == null -> "not installed"
            PackageInfoCompat.getLongVersionCode(installed) >= apkVer ->
                "already installed (v${installed.versionName}) – safe to delete"
            else -> "newer than installed v${installed.versionName}"
        }
        return "$name v${info.versionName} · ${info.packageName} · $status"
    }
}

object Apps {
    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun load(ctx: Context, includeSystem: Boolean): List<AppEntry> {
        val pm = ctx.packageManager
        val ssm = ctx.getSystemService(StorageStatsManager::class.java)
        val user = Process.myUserHandle()
        val out = mutableListOf<AppEntry>()

        @Suppress("DEPRECATION")
        for (ai in pm.getInstalledApplications(0)) {
            if (ai.packageName == ctx.packageName) continue
            val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (system && !includeSystem) continue

            var exact = false
            val size = try {
                val s = ssm.queryStatsForPackage(ai.storageUuid, ai.packageName, user)
                exact = true
                s.appBytes + s.dataBytes + s.cacheBytes
            } catch (t: Throwable) {
                apkSize(ai)
            }
            val version = try {
                pm.getPackageInfo(ai.packageName, 0).versionName ?: ""
            } catch (t: Throwable) {
                ""
            }
            out += AppEntry(
                ai.packageName,
                pm.getApplicationLabel(ai).toString(),
                size, exact, system, version
            )
        }
        return out.sortedByDescending { it.size }
    }

    private fun apkSize(ai: ApplicationInfo): Long {
        var total = File(ai.sourceDir).length()
        ai.splitSourceDirs?.forEach { total += File(it).length() }
        return total
    }
}
