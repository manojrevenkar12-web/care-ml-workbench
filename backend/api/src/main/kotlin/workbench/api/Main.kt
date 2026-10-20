package workbench.api

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val app = Wiring.build(Config.fromEnv())
    embeddedServer(Netty, port = app.config.port, host = "0.0.0.0") { module(app) }.start(wait = true)
}
