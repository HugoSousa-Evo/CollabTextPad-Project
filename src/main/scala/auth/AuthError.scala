package auth

import scala.util.control.NoStackTrace

sealed trait AuthError extends NoStackTrace
object AuthError {
  final case object UserAlreadyExists        extends AuthError
  final case object InvalidFileAccess        extends AuthError
  final case object NoPermissionForOperation extends AuthError
}
