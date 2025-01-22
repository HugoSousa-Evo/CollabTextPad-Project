package auth

import cats.effect.kernel.Sync
import cats.implicits.{catsSyntaxApplicativeId, catsSyntaxOptionId}
import io.circe.Json
import io.circe.generic.JsonCodec
import io.circe.parser.parse
import io.circe.syntax.EncoderOps

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

@JsonCodec
case class Registry private (users: Set[User]) {

  def getUser(name: String): Option[User] = users.find(_.name == name)

  def insertUser(user: User): Registry = new Registry(users + user)

  def isOwnerOf(name: String, filePath: String): Boolean =
    getUserOwnedFiles(name).find(_ == s"$name/$filePath") match {
    case Some(_) => true
    case None => false
  }

  def isMemberOf(name: String, owner: String ,filePath: String): Boolean =
    getUserAssociatedFiles(name).find(_.matches(s"$owner/$filePath")) match {
    case Some(_) => true
    case None => false
  }

  def hasAccess(name: String, owner: String, filePath: String): Boolean =
    isOwnerOf(name, filePath) || isMemberOf(name, owner, filePath)

  def getFilePathFromUser(name: String, owner: String, filePath: String): Option[String] =
    if(hasAccess(name, owner, filePath))
      getUserOwnedFiles(name).find(_ == s"$name/$filePath") match {
        case Some(value) => value.some
        case None => if(name != owner) getUserAssociatedFiles(name).find(_ == s"$owner/$filePath") else None
      }
    else
      None

  def getUserOwnedFiles(name: String): Set[String] = users.find(_.name == name) match {
    case Some(user) =>
      val folderPath = Paths.get(s"./Documents/${user.name}")

      if (Files.exists(folderPath)){
        Files
          .list(folderPath)
          .iterator()
          .asScala
          .map(_.toFile.getName)
          .toSet
          .map((f:String) => s"$name/$f")
      } else { Set.empty }

    case None => Set.empty
  }

  def getUserAssociatedFiles(name: String): Set[String] = users.find(_.name == name) match {
    case Some(user) => user.memberOf
    case None => Set.empty
  }

  def getAllUserFiles(name: String): Set[String] = users.find(_.name == name) match {
    case Some(user) => getUserOwnedFiles(name) ++ user.memberOf
    case None => Set.empty
  }

  def doesFileExist(username: String, owner: String, filePath: String): Boolean =
    getAllUserFiles(username).find(_.matches(s"$owner/$filePath")) match {
      case Some(_) => true
      case None => false
    }

  def makeUserMemberOfFile(guestName: String, ownerName: String, filename: String) : (Registry, Boolean) =
    if(isOwnerOf(ownerName, filename)) {
      getUser(guestName) match {
        case Some(guestUser) =>
          val updatedGuestUser = User(guestUser.name, guestUser.memberOf + s"$ownerName/$filename")
          ( new Registry( (users - guestUser ) + updatedGuestUser) , true)
        case None => (this, false)
      }
    }
    else { (this, false) }

  def update[F[_]: Sync](): F[Path] = Files.write(Registry.path, identity(this).asJson.toString().getBytes).pure[F]
}

object Registry {

  private val path = Paths.get("./src/main/resources/registry.json")

  def load: Registry = {

    val regJsonStr = Files.readString(path)

    parse(regJsonStr).getOrElse(Json.Null).as[Registry] match {
      case Left(_) => new Registry(Set.empty)
      case Right(registry) => registry
    }
  }

}