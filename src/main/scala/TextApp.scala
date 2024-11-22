import cats.effect.kernel.Ref
import cats.effect.std.Queue
import cats.effect.{ExitCode, IO, IOApp}
import entity.Document
import org.http4s.websocket.WebSocketFrame
import services.{DocumentHandler, Server}
import fs2.Stream
import fs2.concurrent.Topic

import java.nio.file.{Files, Paths}
import scala.concurrent.duration.DurationInt

object TextApp extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = {

    //these will be eventually supplied through args
    val fPath = getClass.getClassLoader.getResource("CollabFile.txt").getFile
    val maxClients = 4
    val autoSaveRate = 2

    val fileContents = Files.readString(Paths.get(fPath))

    for {

      documentRef <- Ref.of[IO, Document](Document(fPath, fileContents, 1))

      handler <- DocumentHandler.applyWithWrapper[IO](documentRef)
      messageQueue <- Queue.unbounded[IO, WebSocketFrame]
      topic <- Topic[IO, WebSocketFrame]

      _ <- Stream(
        // run server
        Stream.eval(Server.server[IO](messageQueue, topic, maxClients, handler).useForever.void),
        // write to actual file every x seconds
        Stream.awakeEvery[IO](autoSaveRate.seconds).evalMap(_ => handler.writeToFile),
        // pass data from queue to topic
        Stream.fromQueueUnterminated(messageQueue).through(topic.publish),
        // Websocket "heartbeat" so socket on client doesn't close after some time without new messages
        Stream.awakeEvery[IO](30.seconds).map(_ => WebSocketFrame.Ping()).through(topic.publish)
      ).parJoinUnbounded.compile.drain

    } yield ExitCode.Success
  }
}
