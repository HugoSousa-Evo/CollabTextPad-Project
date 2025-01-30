package auth

import auth.AuthConfig.SecretConfigValue
import cats.implicits._
import cats.effect.Ref
import cats.effect.kernel.Sync
import document.FileError
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}

import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import scala.concurrent.duration.FiniteDuration

trait AuthService[F[_]] {

  def signIn(username: String): F[Either[AuthError, String]]

  def logIn(username: String): F[Either[AuthError, String]]

  def checkFilePath(username: String, owner: String, filename: String): F[Either[FileError, Boolean]]

  def getFilePath(username: String, owner: String, filename: String): F[Either[AuthError, String]]

  def listFileNamesFromUser(username: String): F[Set[String]]

  def userCreateFile(username: String, filename: String): F[Either[FileError, Path]]

  def userDeleteFile(username: String, filename: String): F[Either[AuthError, Boolean]]

  def inviteUser(guestName: String, ownerName: String, filename: String): F[Either[AuthError, Boolean]]

  def getNamesOfAllUsers: F[Set[String]]
}

object AuthService {

  private def issueToken[F[_]: Sync](now: Instant,
                                     username: String,
                                     jwtExpirationTime: FiniteDuration,
                                     jwtSecret: SecretConfigValue[String]
                            ): F[Either[AuthError, String]] = Sync[F].delay {

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
                (newReg, false)
            }
          }

          now <- Sync[F].realTimeInstant

          result <-
            if (userAlreadyExists) AuthError.UserAlreadyExists.asLeft[String].pure[F]
            else {
              userRegistry.get.flatMap(_.update()) *>
              issueToken(now, username, jwtExpirationTime, jwtSecret)
            }

        } yield result
      }

       def logIn(username: String): F[Either[AuthError, String]] = {
         for {

           userAlreadyExists <- userRegistry.modify { registry =>
             registry.getUser(username) match {
               case Some(_) => (registry, true)
               case None => (registry, false)
             }
           }

           now <- Sync[F].realTimeInstant

           result <-
             if (!userAlreadyExists) AuthError.UserDoesNotExist.asLeft[String].pure[F]
             else
               issueToken(now, username, jwtExpirationTime, jwtSecret)

         } yield result
       }

       def checkFilePath(username: String, owner: String, filename: String): F[Either[FileError, Boolean]] = {
         for {
           registry <- userRegistry.get
         } yield
           if (registry.doesFileExist(username, owner, filename)) {
             true.asRight
           } else {
             FileError.FileDoesNotExist.asLeft
           }
       }

       def getFilePath(username: String, owner: String, filename: String): F[Either[AuthError, String]] = {
         for {
           registry <- userRegistry.get
         } yield
           registry.getFilePathFromUser(username,owner,filename) match {
             case Some(filename) => s"./Documents/$filename".asRight
             case None => AuthError.InvalidFileAccess.asLeft
           }
       }


       // --- USER OPERATIONS ---

       def listFileNamesFromUser(username: String): F[Set[String]] =
         for {
           reg <- userRegistry.get
         } yield reg.getAllUserFiles(username)

       def userCreateFile(username: String, filename: String): F[Either[FileError, Path]] =
         for {
           registry <- userRegistry.get
         } yield registry.getFilePathFromUser(username, username, filename) match {
           case Some(_) => FileError.FileAlreadyExists.asLeft
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
           result <- userRegistry.modify { registry =>
             if(registry.isOwnerOf(username, filename)){

               //Delete all mention in guest users
               val updatedUserList = registry.users.map { prevUser =>
                  if (prevUser.memberOf.contains(s"$username/$filename")) {
                    User(prevUser.name, prevUser.memberOf - s"$username/$filename")
                  } else {
                    prevUser
                  }
               }

               (registry.updateAllUsers(updatedUserList), Files.deleteIfExists(Paths.get(s"./Documents/$username/$filename")).asRight)
             }
             else{
               (registry, AuthError.NoPermissionForOperation.asLeft)
             }
           }
           _ <- userRegistry.get.flatMap(_.update())
         } yield result

       def inviteUser(guestName: String, ownerName: String, filename: String): F[Either[AuthError, Boolean]] =
         for {
           b <- userRegistry.modify { registry =>
             val (newReg, success) = registry.makeUserMemberOfFile(guestName, ownerName, filename)
             if(success){
               newReg.update()
               (newReg, success)
             } else { (newReg, success) }
           }

         } yield if(b) b.asRight else AuthError.NoPermissionForOperation.asLeft

       def getNamesOfAllUsers: F[Set[String]] =
         for {
           reg <- userRegistry.get
         } yield reg.getNamesOfAllUsers

     }.pure[F]
}
