package services

import auth.AuthService
import cats.effect.implicits.effectResourceOps
import cats.effect.kernel.Async
import cats.effect.std.Queue
import cats.implicits._
import entity.Operation
import fs2.concurrent.Topic
import fs2.{Pipe, Stream}
import io.circe.Json
import io.circe.parser.parse
import org.http4s.dsl.Http4sDsl
import org.http4s.{AuthedRoutes, HttpApp, HttpRoutes, StaticFile}
import org.http4s.server.middleware.ErrorHandling
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import entity.document.{DocumentConfig, DocumentHandler}
import io.circe.syntax.EncoderOps
import org.http4s.circe._
import org.http4s.server.AuthMiddleware
import pureconfig.ConfigSource
import pureconfig.module.catseffect.syntax.CatsEffectConfigSource
import pureconfig.generic.auto._

sealed trait Route
object Route {

  final case class OpenRoutes[F[_]: Async]
  (service: AuthService[F]) extends Route {

    def userAuthRoute() : HttpRoutes[F] = {

      val dsl = Http4sDsl[F]
      import dsl._

      HttpRoutes.of[F] {
        case POST -> Root / "auth" / "signIn" / username =>
          for {
            signInResult <- service.signIn(username)
            response <- signInResult match {
              case Left(e) =>
                BadRequest(e.toString)
              case Right(authToken) =>
                Ok(authToken.asJson)
            }
          } yield response

        case POST -> Root / "auth" / "logIn" / username =>
          for {
            signInResult <- service.logIn(username)
            response <- signInResult match {
              case Left(e) =>
                BadRequest(e.toString)
              case Right(authToken) =>
                Ok(authToken.asJson)
            }
          } yield response

        case req @ GET -> Root / "signIn" =>
          val path = "./src/main/resources/signUser.html"

          StaticFile.fromPath(fs2.io.file.Path(path), Some(req)).getOrElseF(NotFound())
      }
    }

  }

  final case class AuthRoutes[F[_]: Async]
  (service: AuthService[F], middleware: AuthMiddleware[F, String]) extends Route {

    def textPadRoute(): HttpRoutes[F] = {

      val dsl = Http4sDsl[F]
      import dsl._

      middleware {

        AuthedRoutes.of {

          case authReq @ GET -> Root / _ / "editFile" as username =>

            val path = "./src/main/resources/textpad.html"

            StaticFile.fromPath(fs2.io.file.Path(path), Some(authReq.req)).getOrElseF(NotFound())

          case authReq @ GET -> Root / filename / "file" as username =>

            for {
              filePathResult <- service.getFilePath(username, filename)
              response <- filePathResult match {
                case Left(e) => BadRequest(e.toString)
                case Right(path) =>
                  StaticFile.fromPath(fs2.io.file.Path(path), Some(authReq.req)).getOrElseF(NotFound())
              }
            } yield response

        }
      }
    }

    def wsOperationRoute(wsb: WebSocketBuilder2[F],
                         messageQueue: Queue[F, Operation],
                         topic: Topic[F, WebSocketFrame],
                         handler: DocumentHandler[F]): HttpRoutes[F] = {

      val dsl = Http4sDsl[F]
      import dsl._

      middleware {

        AuthedRoutes.of {

          case GET -> Root / filepath / "editFile" / "ws" as username =>

            val path = s"./Documents/$username/$filepath"

            def send(): Stream[F, WebSocketFrame] = {
              topic.subscribe(maxQueued = 100)
            }

            def receive():
            Pipe[F, WebSocketFrame, Unit] = { stream =>

              stream.through(parseFrameToOperation[F]).foreach {

                case ins @ Operation.Insert(position, content, _) =>
                  handler.insertAt(path, position, content) *> messageQueue.offer(ins)

                case del @ Operation.Delete(position, amount, _) =>
                  handler.deleteAt(path, position, amount) *> messageQueue.offer(del)
              }
            }

            for {

              _ <- handler.open(path)

              response <- wsb.withOnClose(handler.unsubscribe(path)).build(send(), receive())

            } yield response
        }
      }
    }

    def userOperationRoute(): HttpRoutes[F] = {

      val dsl = Http4sDsl[F]
      import dsl._

      middleware {
        AuthedRoutes.of {

          case authReq @ GET -> Root / "userPage" as username =>
            val path = "./src/main/resources/userPage.html"
            StaticFile.fromPath(fs2.io.file.Path(path), Some(authReq.req)).getOrElseF(NotFound())

          case POST -> Root / _ / "createFile" / filename as username =>
            for {
              result <- service.userCreateFile(username, filename)
              response <- result match {
                case Left(e) => BadRequest(e.toString)
                case Right(_) => Ok("file created")
              }
            } yield response

          case POST -> Root / _ / "deleteFile" / filename as username =>
            for {
              result <- service.userDeleteFile(username, filename)
              response <- result match {
                case Left(e) => BadRequest(e.toString)
                case Right(_) => Ok("file deleted")
              }
            } yield response

          case GET -> Root / _ / "listFiles" as username =>
            for {
              files <- service.listFileNamesFromUser(username)
              response <- Ok(files.asJson)
            } yield response
        }
      }

    }
  }

  def routesToApp[F[_]: Async](routeSeq: Seq[HttpRoutes[F]]): HttpApp[F] = {
    routeSeq.reduce(_ <+> _)
  }.orNotFound

  private def parseFrameToOperation[F[_]: Async]: Pipe[F, WebSocketFrame, Operation] = _.collect {
    case text: WebSocketFrame.Text =>
      parse(text.str).getOrElse(Json.Null).as[Operation] match {
        case Left(_) => Operation.emptyInsert
        case Right(value) => value
      }
  }
}