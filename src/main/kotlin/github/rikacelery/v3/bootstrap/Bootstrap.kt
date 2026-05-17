package github.rikacelery.v3.bootstrap

import github.rikacelery.v3.api.ApiClient
import github.rikacelery.v3.components.*
import github.rikacelery.v3.exceptions.RenameException
import github.rikacelery.v3.postprocessors.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.serialization.json.jsonObject
import org.apache.commons.cli.*
import org.slf4j.LoggerFactory
import java.io.File

class Bootstrap(
    private val apiClient: ApiClient,
    private val roomComponent: RoomComponent,
    private val authComponent: AuthComponent,
    private val postProcessorComponent: PostProcessorComponent,
    private val schedulerComponent: SchedulerComponent,
) {
    private val logger = LoggerFactory.getLogger("v3.Bootstrap")

    suspend fun initialize(args: Iterable<String>) {
        val cli = parseCli(args)

        loadUsers(cli)
        loadProcessors(cli)
        loadRooms(cli)

        roomComponent.setReady()
        logger.info("Bootstrap complete")
    }

    data class CliConfig(
        val listConfPath: String = "list.conf",
        val outputDir: String = "out",
        val tmpDir: String = "tmp",
        val port: Int = 8090,
        val usersPath: String = "users.txt",
        val postProcessorPath: String = "postprocessor.json"
    )

    private fun parseCli(args: Iterable<String>): CliConfig {
        val options = Options()
            .addOption("file", true, "list.conf path")
            .addOption("output", true, "output directory")
            .addOption("tmp", true, "temp directory")
            .addOption("port", true, "HTTP port")
            .addOption("users", true, "users.txt path")
            .addOption("post", true, "postprocessor.json path")
        val cmd = DefaultParser().parse(options, args.toList().toTypedArray())
        return CliConfig(
            listConfPath = cmd.getOptionValue("file", "list.conf"),
            outputDir = cmd.getOptionValue("output", "out"),
            tmpDir = cmd.getOptionValue("tmp", "tmp"),
            port = cmd.getOptionValue("port", "8090").toInt(),
            usersPath = cmd.getOptionValue("users", "users.txt"),
            postProcessorPath = cmd.getOptionValue("post", "postprocessor.json")
        )
    }

    private suspend fun loadUsers(cli: CliConfig) {
        val file = File(cli.usersPath)
        if (!file.exists()) {
            logger.warn("users.txt not found"); return
        }
        val cookies = file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val users = cookies.mapNotNull { cookie ->
            try {
                apiClient.getUserFromCookie(cookie.trim())
            } catch (e: Exception) {
                logger.warn("Failed to validate cookie: ${e.message}"); null
            }
        }
        authComponent.tell(LoadUsers(users))
        logger.info("Loaded ${users.size} users")
    }

    private suspend fun loadProcessors(cli: CliConfig) {
        val file = File(cli.postProcessorPath)
        if (!file.exists()) {
            logger.warn("postprocessor.json not found"); return
        }
        val json = file.readText()
        val processors = parseProcessorConfig(json, File(cli.outputDir))
        postProcessorComponent.setProcessors(processors)
        logger.info("Loaded ${processors.size} processors")
    }

    private fun parseProcessorConfig(jsonStr: String, outputDir: File): List<Processor> {
        val processors = mutableListOf<Processor>()
        val arr = kotlinx.serialization.json.Json.parseToJsonElement(jsonStr).jsonObject["default"]?.jsonArray
        requireNotNull(arr)
        for (elem in arr) {
            val obj = elem.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: continue
            val cfg = obj["config"]?.jsonObject ?: kotlinx.serialization.json.JsonObject(emptyMap())
            processors.add(
                when (name) {
                    "FixStamp" -> FixStampProcessor(outputDir)
                    "Move" -> MoveProcessor(
                        cfg["template"]?.jsonPrimitive?.content ?: "{{ROOM_NAME}}", outputDir
                    )

                    "Shell" -> ShellProcessor(
                        cfg["cmd"]?.jsonArray?.map { it.jsonPrimitive.content } ?: listOf("echo"),
                        cfg["noreturn"]?.jsonPrimitive?.boolean ?: false,
                        cfg["remove_input"]?.jsonPrimitive?.boolean ?: false)

                    "Slice" -> SliceProcessor(
                        java.time.Duration.ofSeconds(cfg["duration"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600),
                        outputDir
                    )

                    else -> {
                        logger.warn("Unknown processor: $name"); continue
                    }
                })
        }
        return processors
    }

    data class ListConfLine(
        val url: String, val quality: String = "720p",
        val timeLimit: Long = 0, val sizeLimit: Long = 0, val autoPay: Boolean = false, val armed: Boolean
    )

    private suspend fun loadRooms(cli: CliConfig) {
        val file = File(cli.listConfPath)
        if (!file.exists()) {
            logger.warn("list.conf not found"); return
        }
        val lines = file.readLines().filter { it.isNotBlank() }
        coroutineScope {
            lines.map { line ->
                async {
                    val parsed = parseListConfLine(line) ?: return@async
                    try {
                        val (id, name) = apiClient.getRoomFromUrlOrSlug(parsed.url, parsed.quality)
                        roomComponent.internalAdd(
                            id,
                            name,
                            parsed.quality,
                            parsed.timeLimit,
                            parsed.sizeLimit,
                            parsed.autoPay
                        )
                        if (parsed.armed) {
                            schedulerComponent.internalAdd(id, name, parsed.quality, parsed.armed)
                        }
                    } catch (e: RenameException) {
                        try {
                            val (id, name) = apiClient.getRoomFromUrlOrSlug(e.newName, parsed.quality)
                            roomComponent.internalAdd(
                                id,
                                name,
                                parsed.quality,
                                parsed.timeLimit,
                                parsed.sizeLimit,
                                parsed.autoPay
                            )
                            if (parsed.armed) {
                                schedulerComponent.internalAdd(id, name, parsed.quality, parsed.armed)
                            }
                        } catch (e: Exception) {
                            logger.warn("Failed to load room from '$line': ${e.message}", e)
                        }
                    } catch (e: Exception) {
                        logger.warn("Failed to load room from '$line': ${e.message}", e)
                    }
                }
            }.awaitAll()
        }
        logger.info("Loaded rooms from list.conf")
    }

    private fun parseListConfLine(line: String): ListConfLine? {
        val parts = line.trim().split(" ")
        if (parts.isEmpty()) return null
        val url = parts[0]
        var quality = "720p"
        var timeLimit = 0L
        var sizeLimit = 0L
        var autoPay = false
        for (i in 1 until parts.size) {
            when {
                parts[i].startsWith("q:") -> quality = parts[i].substring(2)
                parts[i].startsWith("limit:") -> timeLimit = parts[i].substring(6).toLong()
                parts[i].startsWith("size:") -> sizeLimit = parseSize(parts[i].substring(5))
                parts[i] == "autopay" -> autoPay = true
            }
        }
        return ListConfLine(url, quality, timeLimit, sizeLimit, autoPay, armed = !line.trim().startsWith("#"))
    }

    private fun parseSize(s: String): Long {
        val regex = Regex("(\\d+)(Ti|Gi|Mi|Ki|Bi|T|G|M|K|B)")
        var total = 0L
        regex.findAll(s).forEach {
            val v = it.groupValues[1].toLong()
            total += when (it.groupValues[2]) {
                "Ti" -> v * 1024 * 1024 * 1024 * 1024
                "Gi" -> v * 1024 * 1024 * 1024
                "Mi" -> v * 1024 * 1024
                "Ki" -> v * 1024
                "Bi" -> v
                "T" -> v * 1000_000_000_000
                "G" -> v * 1000_000_000
                "M" -> v * 1000_000
                "K" -> v * 1000
                "B" -> v
                else -> 0
            }
        }
        return total
    }
}
