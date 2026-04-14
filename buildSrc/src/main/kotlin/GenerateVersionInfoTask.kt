import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class GenerateVersionInfoTask : DefaultTask() {
    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val versionCode: Property<Int>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package misc

            object VersionInfo {
                const val VERSION_NAME = "${versionName.get()}"
                const val VERSION_CODE = ${versionCode.get()}
            }
            """.trimIndent(),
        )
    }
}
