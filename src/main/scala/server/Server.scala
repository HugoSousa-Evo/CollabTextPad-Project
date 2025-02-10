package server

import auth.{AuthConfig, AuthMiddleware, AuthRoutes, AuthService, Registry}
import cats.effect.Resource
import cats.effect.implicits.effectResourceOps
import cats.effect.kernel.{Async, Ref}
import com.comcast.ip4s.IpLiteralSyntax
import document.{DocumentConfig, DocumentHandler}
import fs2.io.net.Network
import org.http4s.ember.server.EmberServerBuilder
import pureconfig.ConfigSource
import pureconfig.generic.auto._
import pureconfig.module.catseffect.syntax._

object Server {

  // Make this a config file as well
  private val serverHost = ipv4"127.0.0.1"
  private val serverPort = port"9002"

  def server[F[+_]: Async: Network](): Resource[F, Unit] = {

    for {

      // Loads the config parameters for the auth service and middleware
      authConfig <- ConfigSource.default.at("auth-config").loadF[F, AuthConfig]().toResource

      // Loads the current User Registry from memory
      registryRef <- Ref.of[F, Registry](Registry.load).toResource

      // Loads general config parameters for all documents
      config <- ConfigSource.default.at("document-config").loadF[F, DocumentConfig]().toResource
      handler <- DocumentHandler.of[F](config.saveRate).toResource

      // Creates the Authentication Service
      authService <- AuthService
        .inMemory[F]( 
          authConfig.jwtSecret, 
          authConfig.jwtExpirationTime,
          userRegistry = registryRef
        ).toResource

      // Creates the Authentication Middleware
      middleware = AuthMiddleware[F](
        jwtSecret = authConfig.jwtSecret
      )

      // Creates the route handlers
      authRouteHandler = AuthRouteHandler[F](authService,middleware)
      openRouteHandler = OpenRouteHandler[F](authService)


      // Builds the server with the desired endpoints
      _ <- EmberServerBuilder
        .default[F]
        .withHost(serverHost)
        .withPort(serverPort)
        .withHttpWebSocketApp(ws => RouteHandler.routesToApp[F]( Seq(

          OpenRoutes.userAuthRoute(openRouteHandler),
          AuthRoutes.textPadRoute(authRouteHandler, handler),
          AuthRoutes.userOperationRoute(authRouteHandler),
          AuthRoutes.wsOperationRoute(ws, handler, authRouteHandler)

        ))).build

    } yield ()

  }
}
