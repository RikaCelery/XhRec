package github.rikacelery.v3.data

import java.io.File

data class SystemConfig(
    val outputDir: File,
    val tmpDir: File,
    val port: Int,
    val proxy: String?,
    val decryptKeys: Map<String, String>,
    val streamAuthKey: String,
    val authToken: String,
    val platformHost: String,
    val listConfPath: String = "list.conf",
    val configPath: String = "xhrec.json",
    val maskSensitiveLogs: Boolean = true
)
