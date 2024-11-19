import cats.effect.kernel.Ref
import cats.effect.std.Queue
import cats.effect.{ExitCode, IO, IOApp}
import org.http4s.websocket.WebSocketFrame
import services.{FileHandler, Server}

import fs2.Stream
import fs2.concurrent.Topic

import java.nio.file.{Files, Paths}
import scala.concurrent.duration.DurationInt

object TextApp extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = {

    //these will be eventually supplied through args
    val f = getClass.getClassLoader.getResource("CollabFile.txt").getFile
    val maxClients = 4
    val updateRate = 2

    for {
      // Eventually refactor this to use a document object instead of a plain string
      ref <- Ref.of[IO, String](Files.readString(Paths.get(f)))

      handler <- IO(FileHandler[IO](f, ref))
      queue <- Queue.unbounded[IO, WebSocketFrame]
      topic <- Topic[IO, WebSocketFrame]

      _ <- Stream(
        // run server
        Stream.eval(Server.server[IO](queue, topic, maxClients, handler).useForever.void),
        // update file every x seconds
        Stream.awakeEvery[IO](updateRate.seconds).evalMap(_ => handler.writeToFile),
        // pass data from queue to topic
        Stream.fromQueueUnterminated(queue).through(topic.publish),
        // Websocket "heartbeat" so socket on client doesn't close after some time without new messages
        Stream.awakeEvery[IO](30.seconds).map(_ => WebSocketFrame.Ping()).through(topic.publish)
      ).parJoinUnbounded.compile.drain

    } yield ExitCode.Success
  }
}
