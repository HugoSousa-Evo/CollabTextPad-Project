import cats.effect.std.Queue
import cats.effect.{ExitCode, IO, IOApp}
import entity.Operation
import fs2.concurrent.Topic
import fs2.Stream
import org.http4s.websocket.WebSocketFrame
import services.server.Server

import scala.concurrent.duration.DurationInt

object TextApp extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = {
    
    for {

      _ <-   Server.server[IO]().useForever.void
    } yield ExitCode.Success
  }
  
}