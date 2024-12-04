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
      messageQueue <- Queue.unbounded[IO, Operation]
      topic <- Topic[IO, WebSocketFrame]
       _ <- Stream(
         Stream.eval(Server.server[IO](messageQueue, topic).useForever.void),
         Stream.fromQueueUnterminated(messageQueue).map(_.operationToTextFrame).through(topic.publish),
         Stream.awakeEvery[IO](30.seconds).map(_ => WebSocketFrame.Ping()).through(topic.publish)
       ).parJoinUnbounded.compile.drain
    } yield ExitCode.Success
  }
  
}