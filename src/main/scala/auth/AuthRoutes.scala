package auth

import cats.effect.kernel.Async
import cats.implicits._
import document.DocumentHandler
import fs2.{Pipe, Stream}
import io.circe.syntax.EncoderOps
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.http4s.{AuthedRoutes, HttpRoutes, StaticFile}
import server.AuthRouteHandler
import server.RouteHandler.parseFrameToOperation

import scala.concurrent.duration.DurationInt

// endpoints protected by authentication middleware
object AuthRoutes {

  // endpoints that serve the files for the text editor
  def textPadRoute[F[_]: Async](handler: AuthRouteHandler[F]): HttpRoutes[F] = {

    val dsl = Http4sDsl[F]
    import dsl._

    handler.middleware {

      AuthedRoutes.of {

        // Serve file for the text editor
        case authReq @ GET -> Root / _ / "editFile" :? ownerParams as _ =>

          val path = "./src/main/resources/textpad.html"

          StaticFile.fromPath(fs2.io.file.Path(path), Some(authReq.req)).getOrElseF(NotFound())

        // Serve the contents from the specified file
        case authReq @ GET -> Root / filename / "file" :? ownerParams as username =>

          val owner = ownerParams("owner").head

          for {
            filePathResult <- handler.service.getFilePath(username, owner, filename)
            response <- filePathResult match {
              case Left(e) => BadRequest(e.toString)
              case Right(path) =>
                StaticFile.fromPath(fs2.io.file.Path(path), Some(authReq.req)).getOrElseF(NotFound())
            }
          } yield response

      }
    }
  }

  // handle websocket connections
  def wsOperationRoute[F[_]: Async](wsb: WebSocketBuilder2[F],
                                    documentHandler: DocumentHandler[F],
                                    routeHandler: AuthRouteHandler[F]): HttpRoutes[F] = {

    val dsl = Http4sDsl[F]
    import dsl._

    routeHandler.middleware {

      AuthedRoutes.of {

        case GET -> Root / filepath / "editFile" / "ws" :? ownerParams as username =>

          val owner = ownerParams("owner").head

          def receive(path: String):
          Pipe[F, WebSocketFrame, Unit] = { stream =>
            stream.through(parseFrameToOperation[F]).foreach { documentHandler.handle(path, _) }
          }

          for {

            filePathResult <- routeHandler.service.getFilePath(username, owner, filepath)

            r <- filePathResult match {
              case Left(e) => BadRequest(e.toString)
              case Right(path) =>
                for {
                  // subscribes user or opens a new session if it isn't already open
                  updateStream <- documentHandler.open(path)
                  // transform Operations objects into text to send
                  operationStream = updateStream.map(_.operationToTextFrame)
                  // makes sure websocket connection is kept alive
                  pingStream = Stream.awakeEvery(30.seconds).map(_ => WebSocketFrame.Ping())

                  sendStream = operationStream.merge(pingStream)

                  // build websocket connection
                  response <- wsb.withOnClose(documentHandler.unsubscribe(path)).build(sendStream, receive(path))

                } yield response
            }

          } yield r
      }
    }
  }

  // endpoints for user actions ( create / delete file, add guest, etc )
  def userOperationRoute[F[_]: Async](handler: AuthRouteHandler[F]): HttpRoutes[F] = {

    val dsl = Http4sDsl[F]
    import dsl._

    handler.middleware {
      AuthedRoutes.of {

        case authReq @ GET -> Root / "userPage" as _ =>
          val path = "./src/main/resources/userPage.html"
          StaticFile.fromPath(fs2.io.file.Path(path), Some(authReq.req)).getOrElseF(NotFound())

        // CREATE NEW FILE
        case POST -> Root / _ / "createFile" / filename as username =>
          for {
            result <- handler.service.userCreateFile(username, filename)
            response <- result match {
              case Left(e) => BadRequest(e.toString)
              case Right(_) => Ok("file created")
            }
          } yield response

        // DELETE FILE
        case POST -> Root / _ / "deleteFile" / filename as username =>
          for {
            result <- handler.service.userDeleteFile(username, filename)
            response <- result match {
              case Left(e) => BadRequest(e.toString)
              case Right(_) => Ok("file deleted")
            }
          } yield response

        // INVITE USER AS A GUEST
        case POST -> Root / "invite" :? params as ownerName =>
          val guest = params("guest").head
          val filename = params("filename").head

          for{
            result <- handler.service.inviteUser(guest, ownerName, filename)
            response <- result match {
              case Left(e) => BadRequest(e.toString)
              case Right(_) => Ok("user invited")
            }
          } yield response

        case GET -> Root / "userList" as username =>
          for {
            usernames <- handler.service.getNamesOfAllUsers
            response <- Ok(usernames.asJson)
          } yield response

        // LIST FILES FOR A USER
        case GET -> Root / user / "listFiles" as username =>
          for {
            files <- handler.service.listFileNamesFromUser(user)
            response <- Ok(files.asJson)
          } yield response
      }
    }

  }
}
