package services

import cats.effect.kernel.Async
import cats.effect.std.Queue
import cats.implicits._
import entity.Operation
import fs2.concurrent.Topic
import fs2.{Pipe, Stream}
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.EncoderOps
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpApp, HttpRoutes, StaticFile}
import org.http4s.server.middleware.ErrorHandling
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame

object Routes {

  def textPadRoute[F[_]: Async](pathRef: F[String]): HttpRoutes[F] = {

    val dsl = Http4sDsl[F]
    import dsl._

    HttpRoutes.of[F] {

      case req @ GET -> Root / "editFile" =>

        StaticFile.fromPath(
          fs2.io.file.Path(this.getClass.getClassLoader.getResource("textpad.html").getFile), Some(req)
        ).getOrElseF(NotFound())

      case req @ GET -> Root / "file" =>

        for {
          filePath <- pathRef
          response <- StaticFile.fromPath( fs2.io.file.Path(filePath), Some(req)).getOrElseF(NotFound())
        } yield response

    }
  }

  private def parseFrameToOperation[F[_]: Async]: Pipe[F, WebSocketFrame, Operation] = _.collect {
    case text: WebSocketFrame.Text =>
      parse(text.str).getOrElse(Json.Null).as[Operation] match {
        case Left(_) => Operation.emptyInsert
        case Right(value) => value
    }
  }

  def wsOperationRoute[F[+_]: Async](wsb: WebSocketBuilder2[F], queue: Queue[F, WebSocketFrame],
                                     topic: Topic[F, WebSocketFrame], maxClients: Int, handler: DocumentHandler[F]
                                   ): HttpRoutes[F] = {
    val dsl = Http4sDsl[F]
    import dsl._

    HttpRoutes.of[F] {

      case GET -> Root / "editFile" / "ws" =>

        val send: Stream[F, WebSocketFrame] = {
          topic.subscribe(maxQueued = maxClients)
        }

        val receive: Pipe[F, WebSocketFrame, Unit] = { stream =>

          stream.through(parseFrameToOperation[F]).foreach {

            case ins: Operation.Insert =>
              handler.insertAt(ins.position, ins.content) *>
                queue.offer(
                  WebSocketFrame.Text(ins.asJson.deepMerge(Map("type" -> "insert").asJson
                  ).toString))

            case del: Operation.Delete =>
              handler.deleteAt(del.position, del.amount) *>
                queue.offer(
                  WebSocketFrame.Text(del.asJson.deepMerge(Map("type" -> "delete").asJson
                  ).toString))
          }
        }

        wsb.build(send, receive)
    }
  }

  def routesToApp[F[_]: Async](routeSeq: Seq[HttpRoutes[F]]): HttpApp[F] = ErrorHandling {
    routeSeq.reduce(_ <+> _)
  }.orNotFound
}
