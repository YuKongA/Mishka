package top.yukonga.mishka.platform

import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

actual class FilePicker {
    actual fun pickYamlFile(onResult: (FilePickResult?) -> Unit) {
        val chooser = JFileChooser().apply {
            fileFilter = FileNameExtensionFilter("YAML files", "yaml", "yml")
            dialogTitle = "选择配置文件"
        }
        val result = chooser.showOpenDialog(null)
        if (result == JFileChooser.APPROVE_OPTION) {
            val file = chooser.selectedFile
            onResult(FilePickResult(file.name, file.readText()))
        } else {
            onResult(null)
        }
    }
}
