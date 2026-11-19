package tc001.viewer

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import tc001.viewer.core.writePngWithText

val VIDEOS: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
private val IMAGES: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

// MediaProvider refuses non-media files under Pictures (only Download and Documents): the .bin goes to Documents.
private val FILES: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

/** Inserts [file] in [dir] of [collection], hidden from other apps until [publish]; our own files need no storage permission. */
fun ContentResolver.pending(collection: Uri, dir: String, file: String, mime: String): Uri {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, file)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    return insert(collection, values) ?: throw IOException("MediaStore refused $dir/$file")
}

fun ContentResolver.publish(uri: Uri) {
    val rows = update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    if (rows != 1) throw IOException("publish $uri updated $rows rows")
}

/** Deletes this app's pending files in [dir], left behind when the process died before publishing them. */
fun ContentResolver.deletePending(collection: Uri, dir: String): Int {
    val where = "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
    val args = arrayOf("$dir/")
    // Pending rows are invisible to queries by default; only the owner sees its own.
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        delete(
            collection,
            Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
            },
        )
    } else {
        @Suppress("DEPRECATION")
        delete(MediaStore.setIncludePending(collection), "${MediaStore.MediaColumns.IS_PENDING}=1 AND $where", args)
    }
}

private fun ContentResolver.write(uri: Uri, write: (OutputStream) -> Unit) =
    (openOutputStream(uri) ?: throw IOException("no stream for $uri")).use(write)

/**
 * Off the source thread: the PNG of the composed [view], carrying [json] as a `tc001` tEXt chunk, and the raw
 * 18,568-byte [frame], published together once both are written; on any failure neither is kept.
 */
fun ContentResolver.savePhoto(name: String, view: Bitmap, frame: ByteArray, json: String): Boolean {
    val uris = ArrayList<Uri>(2)
    return try {
        val png = pending(IMAGES, PHOTO_DIR, "$name.png", "image/png").also(uris::add)
        val raw = pending(FILES, RAW_DIR, "$name.bin", "application/octet-stream").also(uris::add)
        val encoded = ByteArrayOutputStream()
        if (!view.compress(Bitmap.CompressFormat.PNG, 100, encoded)) throw IOException("PNG encoding failed")
        write(png) { writePngWithText(encoded.toByteArray(), PHOTO_KEYWORD, json, it) }
        write(raw) { it.write(frame) }
        uris.forEach { publish(it) }
        Log.i(UsbSource.TAG, "photo saved: $PHOTO_DIR/$name.png, $RAW_DIR/$name.bin")
        true
    } catch (e: Exception) {
        Log.w(UsbSource.TAG, "photo $name not saved", e)
        for (uri in uris) {
            try {
                delete(uri, null, null)
            } catch (d: Exception) {
                Log.w(UsbSource.TAG, "photo: $uri not deleted", d)
            }
        }
        false
    } finally {
        view.recycle()
    }
}

const val PHOTO_DIR = "Pictures/TC001"
const val PHOTO_KEYWORD = "tc001"
const val RAW_DIR = "Documents/TC001"
const val VIDEO_DIR = "Movies/TC001"
