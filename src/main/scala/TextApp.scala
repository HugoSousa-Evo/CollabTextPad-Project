import cats.effect.std.Queue
import cats.effect.{ExitCode, IO, IOApp}
import cats.implicits.toSemigroupKOps
import com.comcast.ip4s.IpLiteralSyntax
import org.http4s.{HttpApp, HttpRoutes, StaticFile}
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.ErrorHandling
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import fs2.{Pipe, Stream}

object TextApp extends IOApp {

  private object Server {

    private val host = ipv4"127.0.0.1"
    private val port = port"9002"

    def server: IO[Unit] = {
      EmberServerBuilder
        .default[IO]
        .withHost(host)
        .withPort(port)
        .withHttpWebSocketApp(ws => Routes.routesToApp(
          Seq(
            Routes.textPadRoute,
            Routes.wsOperationRoute(ws))
        ))
        .build
        .useForever
        .void
    }
  }

  private object Routes {

    def textPadRoute: HttpRoutes[IO] = HttpRoutes.of[IO] {

      // curl "localhost:9002/editFile"
      case req @ GET -> Root / "editFile"=>

        StaticFile.fromPath(
          fs2.io.file.Path(getClass.getClassLoader.getResource("textpad.html").getFile), Some(req)
        ).getOrElseF(NotFound())
    }

    def wsOperationRoute(wsb: WebSocketBuilder2[IO]): HttpRoutes[IO] = {

      HttpRoutes.of[IO] {

        // curl "localhost:9002/editFile/ws"
        case req@GET -> Root / "editFile" / "ws" =>

          val wrappedQueue: IO[Queue[IO, WebSocketFrame]] = {
            Queue.unbounded[IO, WebSocketFrame]
          }

          wrappedQueue.flatMap { actualQueue =>

            val send: Stream[IO, WebSocketFrame] = {
              Stream.fromQueueUnterminated(actualQueue)
            }

            val receive: Pipe[IO, WebSocketFrame, Unit] = {

              val source = scala.io.Source.fromFile(getClass.getClassLoader.getResource("CollabFile.txt").getFile)
              val lines = try source.getLines().mkString("\n") finally source.close()

              //            _.foreach{
              //              case text: WebSocketFrame.Text => IO.println(text.str)
              //            }

              _.foreach(_ => actualQueue.offer(WebSocketFrame.Text(lines)))
            }

            wsb.build(send, receive)
          }
      }

    }

    def routesToApp(routeSeq: Seq[HttpRoutes[IO]]): HttpApp[IO] = ErrorHandling {
      routeSeq.reduce(_ <+> _)
    }.orNotFound
  }

  override def run(args: List[String]): IO[ExitCode] = {
      for {
        _ <- Server.server
      } yield ExitCode.Success
  }
}
