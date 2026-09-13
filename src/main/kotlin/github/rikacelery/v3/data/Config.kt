package github.rikacelery.v3.data

import java.io.File

data class SystemConfig(
    val outputDir: File,
    val tmpDir: File,
    val port: Int,
    val tls: Boolean = true,
    val proxy: String?,
    val decryptKeys: Map<String, String>,
    val streamAuthKey: String,
    val hosts: HostsConfig = HostsConfig.DEFAULT,
    val listConfPath: String = "list.conf",
    val configPath: String = "xhrec.json",
    val maskSensitiveLogs: Boolean = true,
    val apiToken: String = "",
    /**
     * Root log level chosen in the dashboard, or `""` to keep whatever `logback.xml` configures.
     * Applied at startup and whenever the dashboard changes it.
     */
    val logLevel: String = ""
)
