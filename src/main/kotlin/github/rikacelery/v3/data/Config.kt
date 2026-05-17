package github.rikacelery.v3.data

import java.io.File

data class SystemConfig(
    val outputDir: File,
    val tmpDir: File,
    val port: Int,
    val proxy: String?,
    val decryptKeys: Map<String, String>,
    val authToken: String,
    val platformHost: String
)

data class ProcessorConfig(
    val type: String,
    val config: Map<String, String>
)
