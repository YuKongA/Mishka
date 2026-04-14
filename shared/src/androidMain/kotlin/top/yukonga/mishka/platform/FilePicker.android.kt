package top.yukonga.mishka.platform

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns

actual class FilePicker(private val activity: Activity) {

    private var callback: ((FilePickResult?) -> Unit)? = null

    actual fun pickYamlFile(onResult: (FilePickResult?) -> Unit) {
        callback = onResult
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        activity.startActivityForResult(intent, FILE_PICK_REQUEST_CODE)
    }

    fun handleResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != FILE_PICK_REQUEST_CODE) return
        if (resultCode != Activity.RESULT_OK || data?.data == null) {
            callback?.invoke(null)
            callback = null
            return
        }
        val uri: Uri = data.data!!
        try {
            val fileName = getFileName(uri)
            val content = activity.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: ""
            callback?.invoke(FilePickResult(fileName, content))
        } catch (_: Exception) {
            callback?.invoke(null)
        }
        callback = null
    }

    private fun getFileName(uri: Uri): String {
        var name = "imported.yaml"
        activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    companion object {
        const val FILE_PICK_REQUEST_CODE = 1002
    }
}
