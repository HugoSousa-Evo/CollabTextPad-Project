package services.server

import auth.{AuthConfig, AuthMiddleware, AuthService}
import cats.effect.Resource
import cats.effect.implicits.effectResourceOps
import cats.effect.kernel.{Async, Ref}
import com.comcast.ip4s.IpLiteralSyntax
import entity.Registry
import fs2.io.net.Network
import org.http4s.ember.server.EmberServerBuilder
import pureconfig.ConfigSource
import pureconfig.generic.auto._
import pureconfig.module.catseffect.syntax._
import services.Route

object Server {

  private val serverHost = ipv4"127.0.0.1"
  private val serverPort = port"9002"

  def server[F[+_]: Async: Network](): Resource[F, Unit] = {

    for {

      authConfig <- ConfigSource.default.at("auth-config").loadF[F, AuthConfig]().toResource

      registryRef <- Ref.of[F, Registry](Registry.load).toResource

      authService <- AuthService
        .inMemory[F]( 
          authConfig.jwtSecret, 
          authConfig.jwtExpirationTime,
          userRegistry = registryRef
        ).toResource

      middleware = AuthMiddleware[F](
        jwtSecret = authConfig.jwtSecret
      )

      authRoutes = Route.AuthRoutes[F](authService,middleware)
      openRoutes = Route.OpenRoutes[F](authService)

      _ <- EmberServerBuilder
        .default[F]
        .withHost(serverHost)
        .withPort(serverPort)
        .withHttpWebSocketApp(ws => Route.routesToApp[F]( Seq(

          openRoutes.userAuthRoute(),
          authRoutes.textPadRoute(),
          authRoutes.userOperationRoute(),
          authRoutes.wsOperationRoute(ws)

        ))).build

    } yield ()

  }
}
