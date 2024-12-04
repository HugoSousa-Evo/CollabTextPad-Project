package services.server

import auth.{AuthConfig, AuthMiddleware, AuthService}
import cats.effect.Resource
import cats.effect.implicits.effectResourceOps
import cats.effect.kernel.{Async, Ref}
import cats.effect.std.Queue
import com.comcast.ip4s.IpLiteralSyntax
import entity.document.{DocumentConfig, DocumentHandler}
import entity.{Operation, Registry}
import fs2.concurrent.Topic
import fs2.io.net.Network
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.websocket.WebSocketFrame
import pureconfig.ConfigSource
import pureconfig.generic.auto._
import pureconfig.module.catseffect.syntax._
import services.Route

object Server {

  private val serverHost = ipv4"127.0.0.1"
  private val serverPort = port"9002"

  def server[F[+_]: Async: Network](queue: Queue[F, Operation], topic: Topic[F, WebSocketFrame]): Resource[F, Unit] = {

    for {

      authConfig <- ConfigSource.default.at("auth-config").loadF[F, AuthConfig]().toResource

      registryRef <- Ref.of[F, Registry](Registry.load).toResource


      config <- ConfigSource.default.at("document-config").loadF[F, DocumentConfig]().toResource
      handler <- DocumentHandler.of[F](config.saveRate).toResource

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
          authRoutes.wsOperationRoute(ws, queue, topic, handler)

        ))).build

    } yield ()

  }
}
