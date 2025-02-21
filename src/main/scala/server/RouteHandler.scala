package server

import auth.AuthService
import cats.effect.kernel.Async
import cats.implicits._
import document.Operation
import fs2.Pipe
import io.circe.Json
import io.circe.parser.parse
import org.http4s.headers.Origin
import org.http4s.server.AuthMiddleware
import org.http4s.server.middleware.{CORS, ErrorHandling}
import org.http4s.websocket.WebSocketFrame
import org.http4s.{HttpApp, HttpRoutes, Uri}

sealed trait RouteHandler
object RouteHandler {

  private val corsService = CORS.policy.withAllowOriginHost(_.host.value.matches("localhost")).withAllowCredentials(true)

  // utils method to join provided routes to a HttpApp
  def routesToApp[F[_]: Async](routeSeq: Seq[HttpRoutes[F]]): HttpApp[F] = corsService(ErrorHandling {
    routeSeq.reduce(_ <+> _)
  }.orNotFound)

  // utils method to parse the Json received into Operations objects
  def parseFrameToOperation[F[_]: Async]: Pipe[F, WebSocketFrame, Operation] = _.collect {
    case text: WebSocketFrame.Text =>
      parse(text.str).getOrElse(Json.Null).as[Operation] match {
        case Left(_) => Operation.emptyInsert
        case Right(value) => value
      }
  }
}

// endpoints that don't need any kind of authentication
final case class OpenRouteHandler[F[_]: Async](service: AuthService[F]) extends RouteHandler

// endpoints protected by authentication middleware
final case class AuthRouteHandler[F[_]: Async](service: AuthService[F], middleware: AuthMiddleware[F, String]) extends RouteHandler