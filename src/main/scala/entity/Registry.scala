package entity

import entity.Registry.path
import io.circe.generic.JsonCodec
import io.circe.parser.parse
import io.circe.Json
import io.circe.syntax.EncoderOps

import java.nio.file.{Files, Path}

@JsonCodec
case class Registry private (users: Set[User]) {

  def getUser(name: String): Option[User] = users.find(_.name == name)

  def insertUser(user: User): Registry = new Registry(users + user)

  def isOwnerOf(name: String, filePath: String): Boolean = getUserOwnedFiles(name).find(_ == filePath) match {
    case Some(_) => true
    case None => false
  }

  def isMemberOf(name: String, filePath: String): Boolean = getUserAssociatedFiles(name).find(_ == filePath) match {
    case Some(_) => true
    case None => false
  }

  def hasAccess(name: String, filePath: String): Boolean = getUserOwnedFiles(name).find(_ == filePath) match {
    case Some(_) => true
    case None => false
  }

  def getFilePathFromUser(name: String, filePath: String): Option[String] =
    if(hasAccess(name, filePath))
      getAllUserFiles(name).find(_ == filePath)
    else
      None

  def getUserOwnedFiles(name: String): Set[String] = users.find(_.name == name) match {
    case Some(user) => user.ownerOf
    case None => Set.empty
  }

  def getUserAssociatedFiles(name: String): Set[String] = users.find(_.name == name) match {
    case Some(user) => user.memberOf
    case None => Set.empty
  }

  def getAllUserFiles(name: String): Set[String] = users.find(_.name == name) match {
    case Some(user) => user.ownerOf ++ user.memberOf
    case None => Set.empty
  }

  def update(): Unit = Files.write(path, identity(this).asJson.toString().getBytes)
}

object Registry {

  private val path = Path.of(getClass.getClassLoader.getResource("registry.json").getFile)

  def load: Registry = {

    val regJsonStr = Files.readString(path)

    parse(regJsonStr).getOrElse(Json.Null).as[Registry] match {
      case Left(_) => new Registry(Set.empty)
      case Right(registry) => registry
    }
  }

}