package top.yukonga.mishka.platform

/**
 * 文件选择结果
 */
data class FilePickResult(
    val fileName: String,
    val content: String,
)

/**
 * 平台文件选择器接口
 */
expect class FilePicker {
    fun pickYamlFile(onResult: (FilePickResult?) -> Unit)
}
