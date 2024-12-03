package auth

import auth.AuthConfig.SecretConfigValue
import cats.implicits._
import cats.effect.Ref
import cats.effect.kernel.Sync
import entity.document.FileError
import entity.{Registry, User}
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}

import java.nio.file.{Files, Path, Paths}
import scala.concurrent.duration.FiniteDuration

trait AuthService[F[_]] {

  def signIn(username: String): F[Either[AuthError, String]]

  def getFilePath(username: String, filename: String): F[Either[AuthError, String]]

  def listFileNamesFromUser(username: String): F[Set[String]]

  def userCreateFile(username: String, filename: String): F[Either[FileError, Path]]

  def userDeleteFile(username: String, filename: String): F[Either[AuthError, Boolean]]
}

object AuthService {

  def inMemory[F[_]: Sync](
                            jwtSecret: SecretConfigValue[String],
                            jwtExpirationTime: FiniteDuration,
                            userRegistry: Ref[F, Registry]
                          ): F[AuthService[F]] =
     new AuthService[F] {

       def signIn(username: String): F[Either[AuthError, String]] = {
        for {

          userAlreadyExists <- userRegistry.modify { registry =>
            registry.getUser(username) match {
              case Some(_) => (registry, true)
              case None =>
                val newReg = registry.insertUser(User(username, Set.empty))
                newReg.update()
                (newReg, false)
            }
          }

          now <- Sync[F].realTimeInstant

          result <-
            if (userAlreadyExists) AuthError.UserAlreadyExists.asLeft[String].pure[F]
            else
              Sync[F].delay {
                val issuedAtSeconds = now.getEpochSecond

                val claim = JwtClaim(
                  subject = username.some,
                  issuedAt = issuedAtSeconds.some,
                  expiration = (issuedAtSeconds + jwtExpirationTime.toSeconds).some
                )

                Jwt.encode(
                    claim = claim,
                    algorithm = JwtAlgorithm.HS512,
                    key = jwtSecret.value
                  ).asRight[AuthError]
              }

        } yield result
      }

       def getFilePath(username: String, filename: String): F[Either[AuthError, String]] = {
         for {
           registry <- userRegistry.get
         } yield
           registry.getFilePathFromUser(username, filename) match {
             case Some(path) => path.asRight
             case None => AuthError.InvalidFileAccess.asLeft
           }
       }

       def listFileNamesFromUser(username: String): F[Set[String]] =
         for {
           reg <- userRegistry.get
         } yield reg.getAllUserFiles(username)

       def userCreateFile(username: String, filename: String): F[Either[FileError, Path]] =
         for {
           registry <- userRegistry.get
         } yield registry.getFilePathFromUser(username, filename) match {
           case Some(_) => FileError.FileAlreadyExistsForUser.asLeft
           case None => {

             val folderPath = Paths.get(s"./Documents/$username")

             if(!Files.exists(folderPath)){
               Files.createDirectory(folderPath)
             }

             Files.createFile(folderPath.resolve(filename))
           }.asRight
         }

       def userDeleteFile(username: String, filename: String): F[Either[AuthError, Boolean]] =
         for {
           registry <- userRegistry.get
         } yield {
           if(registry.isOwnerOf(username, filename)){
               Files.deleteIfExists(Paths.get(s"./Documents/$username/$filename")).asRight
           }
           else{
             AuthError.NoPermissionForOperation.asLeft
           }
         }

     }.pure[F]
}
