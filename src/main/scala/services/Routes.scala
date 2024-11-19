package services

import cats.effect.kernel.Async
import cats.effect.std.Queue
import cats.implicits._

import entity.Operation

import fs2.{Pipe, Stream}

import io.circe.Json
import io.circe.parser.parse

import org.http4s.{HttpApp, HttpRoutes, StaticFile}
import org.http4s.dsl.io._
import org.http4s.server.middleware.ErrorHandling
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame


object Routes {

  def textPadRoute[F[_]: Async](path: String): HttpRoutes[F] = HttpRoutes.of[F] {

    case req @ GET -> Root / "editFile"=>

      StaticFile.fromPath(
        fs2.io.file.Path(getClass.getClassLoader.getResource("textpad.html").getFile), Some(req)
      ).getOrElseF(???)

    case req @ GET -> Root / "file" =>

      StaticFile.fromPath(
        fs2.io.file.Path(path), Some(req)
      ).getOrElseF(???)
  }

  def wsOperationRoute[F[_]: Async](
                                     wsb: WebSocketBuilder2[F],
                                     queue: Queue[F, WebSocketFrame],
                                     handler: FileHandler[F]
                                   ): HttpRoutes[F] =

    HttpRoutes.of[F] {

      case GET -> Root / "editFile" / "ws" =>

          val send: Stream[F, WebSocketFrame] = {
            Stream.fromQueueUnterminated(queue)
          }

          val receive: Pipe[F, WebSocketFrame, Unit] = {

            _.foreach {

              case text: WebSocketFrame.Text =>

                (parse(text.str).getOrElse(Json.Null).as[Operation] match {
                  case Left(_) => Operation.emptyInsert
                  case Right(value) => value
                })
                match {
                  case Operation.Insert(position, content) => handler.fileInsert(position, content)

                  case Operation.Delete(position, amount) => handler.fileDelete(position, amount)
                }
            }
          }

          wsb.build(send, receive)
    }

  def routesToApp[F[_]: Async](routeSeq: Seq[HttpRoutes[F]]): HttpApp[F] = ErrorHandling {
    routeSeq.reduce(_ <+> _)
  }.orNotFound
}
