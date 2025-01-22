package server

import cats.effect.kernel.Async
import cats.implicits._
import io.circe.syntax.EncoderOps
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpRoutes, StaticFile}

// endpoints that don't need any kind of authentication
object OpenRoutes {

  // endpoints that handle user authentication
  def userAuthRoute[F[_]: Async](handler: OpenRouteHandler[F]) : HttpRoutes[F] = {

    val dsl = Http4sDsl[F]
    import dsl._

    HttpRoutes.of[F] {
      // signsIn (Registers) a new user, sends a BadRequest if the user already exists
      case POST -> Root / "auth" / "signIn" / username =>
        for {
          signInResult <- handler.service.signIn(username)
          response <- signInResult match {
            case Left(e) =>
              BadRequest(e.toString)
            case Right(authToken) =>
              Ok(authToken.asJson)
          }
        } yield response

      // logsIn an existent user, sends a BadRequest if the user isn't registered
      case POST -> Root / "auth" / "logIn" / username =>
        for {
          signInResult <- handler.service.logIn(username)
          response <- signInResult match {
            case Left(e) =>
              BadRequest(e.toString)
            case Right(authToken) =>
              Ok(authToken.asJson)
          }
        } yield response

      // Serves the sign/login page file
      case req @ GET -> Root / "signIn" =>
        val path = "./src/main/resources/signUser.html"

        StaticFile.fromPath(fs2.io.file.Path(path), Some(req)).getOrElseF(NotFound())
    }
  }
}
