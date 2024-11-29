package auth

import auth.AuthConfig.SecretConfigValue

import scala.concurrent.duration.FiniteDuration

case class AuthConfig (jwtSecret: SecretConfigValue[String],
                       jwtExpirationTime: FiniteDuration)

object AuthConfig {

  case class SecretConfigValue[T](value: T) extends AnyVal {
    override def toString: String = "secret"
  }
}