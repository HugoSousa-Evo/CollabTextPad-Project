package auth

import auth.AuthConfig.SecretConfigValue
import cats.data.{Kleisli, NonEmptyList, OptionT}
import cats.effect.kernel.Sync
import org.http4s.Credentials.Token
import org.http4s.{AuthScheme, Request, RequestCookie}
import org.http4s.headers.{Authorization, Cookie}
import org.http4s.server.AuthMiddleware
import pdi.jwt.{Jwt, JwtAlgorithm, JwtOptions}

object AuthMiddleware {

  def apply[F[_]: Sync](jwtSecret: SecretConfigValue[String]): AuthMiddleware[F, String] = {

    def getBearerTokenFromCookie(request: Request[F]): Option[String] =
      request.headers
        .get[Cookie]
        .collect { case Cookie(values) =>
          values.find(_.name.matches("token"))
        }.fold(Option.empty[String])(_.map(_.content))

    def getBearerToken(request: Request[F]): Option[String] =
      request.headers
        .get[Authorization]
        .collect { case Authorization(Token(AuthScheme.Bearer, token)) =>
          token
        }
        .orElse {
          // Fallback for WS routes
          request.params.get("authToken")
        }

    val authUser = Kleisli { request: Request[F] =>
      for {
        token <- OptionT.fromOption[F](getBearerToken(request))
        jwtClaim <- OptionT(
          Sync[F].delay {
            Jwt
              .decode(
                token = token,
                key = jwtSecret.value,
                algorithms = Seq(JwtAlgorithm.HS512),
                options = JwtOptions.DEFAULT
              )
              .toOption
          }
        )
        username <- OptionT.fromOption[F](jwtClaim.subject)
      } yield username
    }

    org.http4s.server.AuthMiddleware.withFallThrough(authUser)
  }
}
