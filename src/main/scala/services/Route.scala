package services

import auth.AuthService
import cats.effect.implicits.effectResourceOps
import cats.effect.kernel.{Async, Ref}
import cats.effect.std.Queue
import cats.implicits._
import entity.{Operation, Registry, User}
import fs2.concurrent.Topic
import fs2.{Pipe, Stream}
import fs2.io.file.Path
import io.circe.Json
import io.circe.parser.parse
import org.http4s.dsl.Http4sDsl
import org.http4s.{AuthedRoutes, HttpApp, HttpRoutes, StaticFile}
import org.http4s.server.middleware.ErrorHandling
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import entity.document.{DocumentConfig, DocumentHandler}
import org.http4s.server.AuthMiddleware
import pureconfig.ConfigSource
import pureconfig.module.catseffect.syntax.CatsEffectConfigSource
import pureconfig.generic.auto._

sealed trait Route
object Route {

  final case class OpenRoutes[F[_]: Async](service: AuthService[F]) extends Route {

    def userAuthRoute() : HttpRoutes[F] = {

      val dsl = Http4sDsl[F]
      import dsl._

      HttpRoutes.of[F] {
        case POST -> Root / "auth" / "signIn" / username =>
          for {
            signInResult <- service.signIn(username)
            response <- signInResult match {
              case Left(e) => println("userExists")
                BadRequest(e.toString)
              case Right(authToken) => println("userAdded")
                Ok(authToken)
            }
          } yield response

        case req @ GET -> Root / "signIn" =>
          val path = this.getClass.getClassLoader.getResource("signUser.html").getFile

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

        AuthedRoutes.of[String, F] {

          case authReq @ GET -> Root / "editFile" as username =>

            val path = this.getClass.getClassLoader.getResource("textpad.html").getFile

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

    def wsOperationRoute(wsb: WebSocketBuilder2[F]): HttpRoutes[F] = {

      val dsl = Http4sDsl[F]
      import dsl._

      middleware {

        AuthedRoutes.of {

          case GET -> Root / filepath / "editFile" / "ws" as username =>

            def send(topic: Topic[F, WebSocketFrame]): Stream[F, WebSocketFrame] = {
              topic.subscribe(maxQueued = 100)
            }

            def receive(messageQueue: Queue[F, Operation], handler: DocumentHandler[F]): Pipe[F, WebSocketFrame, Unit] = { stream =>

              stream.through(parseFrameToOperation[F]).foreach {

                case ins @ Operation.Insert(position, content, _) =>
                  handler.insertAt(position, content) *> messageQueue.offer(ins)

                case del @ Operation.Delete(position, amount, _) =>
                  handler.deleteAt(position, amount) *> messageQueue.offer(del)
              }
            }

            (for {

              config <- ConfigSource.default.at("document-config").loadF[F, DocumentConfig].toResource
              handler <- DocumentHandler.make(filepath, config.saveRate)

              queue <- Queue.unbounded[F, Operation].toResource
              topic <-  Topic[F, WebSocketFrame].toResource

            } yield wsb.build(send(topic), receive(queue, handler))).use(ws => ws)
        }
      }
    }

    def userOperationRoute(): HttpRoutes[F] = {

      val dsl = Http4sDsl[F]
      import dsl._

      middleware {
        AuthedRoutes.of {

          case req @ GET -> Root / "insertFile" / filename as username =>
            ???

          case req @ GET -> Root / "deleteFile" / filename as username =>
            ???

          case req @ GET -> Root / "listFiles" as username =>
            ???
        }
      }

    }
  }

  def routesToApp[F[_]: Async](routeSeq: Seq[HttpRoutes[F]]): HttpApp[F] = ErrorHandling {
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
