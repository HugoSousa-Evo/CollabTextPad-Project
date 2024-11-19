import cats.effect.kernel.Ref
import cats.effect.std.Queue
import cats.effect.{ExitCode, IO, IOApp}
import org.http4s.websocket.WebSocketFrame
import services.{FileHandler, Server}
import fs2.Stream

import java.nio.file.{Files, Paths}
import scala.concurrent.duration.DurationInt

object TextApp extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = {

    val f = getClass.getClassLoader.getResource("CollabFile.txt").getFile

    for {

      ref <- Ref.of[IO, String](Files.readString(Paths.get(f)))

      handler <- IO(FileHandler[IO](f, ref))
      queue <- Queue.unbounded[IO, WebSocketFrame]

      _ <- Stream(
        // run server
        Stream.eval(Server.server[IO](queue, handler).useForever.void),
        // update file every x seconds
        Stream.awakeEvery[IO](2.seconds).evalMap(_ => handler.writeToFile)
      ).parJoinUnbounded.compile.drain

    } yield ExitCode.Success
  }
}
