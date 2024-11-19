package services

import cats.effect.Resource
import cats.effect.kernel.Async
import cats.effect.std.Queue
import com.comcast.ip4s.IpLiteralSyntax
import fs2.io.net.Network
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import org.http4s.websocket.WebSocketFrame

object Server {

  private val host = ipv4"127.0.0.1"
  private val port = port"9002"

  def server[F[_]: Async: Network](
                         queue: Queue[F, WebSocketFrame],
                         fileHandler: FileHandler[F]
                         ): Resource[F, Server] = {
    EmberServerBuilder
      .default[F]
      .withHost(host)
      .withPort(port)
      .withHttpWebSocketApp(ws => Routes.routesToApp[F](
        Seq(
          Routes.textPadRoute[F](fileHandler.path),
          Routes.wsOperationRoute[F](ws, queue, fileHandler))
      ))
      .build
  }
}