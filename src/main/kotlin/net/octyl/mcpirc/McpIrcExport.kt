/*
 * This file is part of mcp-irc-export, licensed under the MIT License (MIT).
 *
 * Copyright (c) Kenzie Togami <https://octyl.net>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package net.octyl.mcpirc

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.defaultLazy
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.pircbotx.Configuration
import org.pircbotx.PircBotX
import org.pircbotx.cap.SASLCapHandler
import org.pircbotx.hooks.Event
import org.pircbotx.hooks.ListenerAdapter
import org.pircbotx.hooks.events.ConnectEvent
import org.pircbotx.hooks.events.DisconnectEvent
import org.pircbotx.hooks.events.IncomingChatRequestEvent
import org.pircbotx.hooks.events.NickAlreadyInUseEvent
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.net.ssl.SSLSocketFactory

class McpIrcExport : CliktCommand(
    help = """
        Given a list of chat lines, pass them to the MCP Bot over DCC.
    """.trimIndent()
) {
    private val logger = KotlinLogging.logger { }

    private val input by argument().path(exists = true, folderOkay = false, readable = true)
    private val name by option(help = "IRC Name").required()
    private val login by option(help = "IRC Login / Identification").defaultLazy { name }
    private val commandRate by option(help = "Command rate, in C/s").int().default(1)
    private val host by option(help = "IRC Host").default("irc.esper.net")
    private val port by option(help = "IRC Port").int().default(6697)
    private val ssl by option("--ssl", "-s", help = "Use SSL sockets (default true)")
        .flag("--no-ssl", default = true)
    private val target by option(help = "Target for DCC request").default("MCPBot_Reborn")
    private val targetHost by option(help = "Target's host, to ensure correct contact")
        .default("mcpbot.bspk.rs")
    private val dccInit by option(help = "DCC Request Message").default("!dcc")

    override fun run() {
        val password = String(System.console().readPassword("NickServ password for $login: "))

        // in some cases `input` may not be a regular file, and may not allow double-reading
        // e.g. pipe / input from terminal
        val regularInput = ensureRegularFile()
        val nLines = Files.lines(regularInput).use { it.count() }
        val lines = flow {
            Files.newBufferedReader(regularInput).useLines {
                println("Emitting all...")
                emitAll(it.asFlow())
            }
        }
            .flowOn(Dispatchers.IO)
            .onEach {
                delay((1000.0 / commandRate.toDouble()).toLong())
            }
        val config = Configuration.Builder()
            .setName(name)
            .setLogin(login)
            .setEncoding(StandardCharsets.UTF_8)
            .setRealName("McpIrcExport")
            .addCapHandler(SASLCapHandler(login, password))
            .also { config ->
                if (ssl) {
                    config.socketFactory = SSLSocketFactory.getDefault()
                }
            }
            .addListener(McpListener(nLines, lines))
            .buildForServer(host, port)
        logger.info { "Connecting to $host/$port${if (ssl) "+" else ""}" }
        PircBotX(config).startBot()
    }

    private fun ensureRegularFile(): Path {
        return when {
            Files.isRegularFile(input) -> input
            else -> {
                val file = Files.createTempFile("mcp-irc-export-commands", ".txt")
                Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING)
                file
            }
        }
    }

    private inner class McpListener(
        private val nLines: Long,
        private val lines: Flow<String>
    ) : ListenerAdapter() {
        private val logger = KotlinLogging.logger { }
        @Volatile
        private var finished = false

        override fun onNickAlreadyInUse(event: NickAlreadyInUseEvent) {
            logger.error {
                "Nick '$name' is already in use. Restart the program with a different name."
            }
            finished = true
            event.bot.sendIRC().quitServer("Sorry!")
        }

        override fun onConnect(event: ConnectEvent) {
            // Open DCC with target
            logger.info { "Connected, sending `$dccInit` to $target" }
            event.bot.sendIRC().message(target, dccInit)
        }

        override fun onIncomingChatRequest(event: IncomingChatRequestEvent) {
            logger.info { "Received DCC request from ${event.userHostmask}" }
            if (event.userHostmask.nick != target || event.userHostmask.hostname != targetHost) {
                logger.warn {
                    """
                    Received request from mis-matched user, ignoring.
                    Hostmask: ${event.userHostmask}. Expected nick: $target. Expected host: $targetHost.
                    """.trimIndent().trim()
                }
                return
            }
            val chat = event.accept()
            GlobalScope.launch(CoroutineName("DCC I/O")) {
                try {
                    coroutineScope {
                        launch {
                            logger.info { "Sending $nLines lines to $target" }
                            var sentLines = 0
                            lines.collect { line ->
                                logger.info { "[DCC] $name: $line" }
                                withContext(Dispatchers.IO) {
                                    chat.sendLine(line)
                                }
                                sentLines++
                                val pcent = "%.2f".format((sentLines / nLines.toDouble()) * 100)
                                logger.info { "Sent $sentLines/$nLines ($pcent%)" }
                            }
                            finished = true
                            // Send out our special key command
                            // This command has known output (unfortunately I couldn't find a way
                            // to have MCPBot reply with output I give it, or this would be more
                            // effective against command file interference)
                            withContext(Dispatchers.IO) {
                                chat.sendLine("!gm ClickEvent.equals 1.15.1")
                            }
                            logger.info {
                                "[IRC Export] Transfer finished. Waiting for $target to reply with final command..."
                            }
                        }
                        launch {
                            // Expect one line for each line
                            while (true) {
                                val line = withContext(Dispatchers.IO) { chat.readLine() }
                                if (line.filterNot { it.category == CharCategory.CONTROL }
                                        .startsWith("=== MC 1.15.1: net/minecraft/util/text/event/ClickEvent.equals")) {
                                    // This is the reply to our key command
                                    logger.info { "[IRC Export] Detected final command output. Closing connection." }
                                    break
                                }
                                logger.info { "[DCC] $target: $line" }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    logger.error(e) { "Error processing lines" }
                } finally {
                    withContext(Dispatchers.IO) {
                        chat.close()
                        event.bot.sendIRC().quitServer(when {
                            finished -> "Thanks!"
                            else -> "Sorry!"
                        })
                    }
                }
            }
        }

        override fun onDisconnect(event: DisconnectEvent) {
            if (!finished) {
                logger.warn(event.disconnectException) { "Unexpected disconnect" }
            }
            event.bot.stopBotReconnect()
            event.bot.close()
        }
    }

}

private val Event.bot: PircBotX get() = getBot()

fun main(args: Array<String>) {
    McpIrcExport().main(args)
}
