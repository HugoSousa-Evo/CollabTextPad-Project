package services

import cats.effect.kernel.{Async, Ref}
import cats.implicits._
import entity.Document

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

case class DocumentHandler[F[_]: Async](documentRef: Ref[F,Document]) {

  def docPath: F[String] = for { document <- documentRef.get } yield document.path

  def insertAt(position: Int, content: String): F[Unit] =
    documentRef.update(document => {

      val newContent = position match {
        case p if p == 0 => content + document.content
        case p if p >= document.content.length => document.content + content
        case _ => document.content.substring(0, position) + content + document.content.substring(position)
      }

      Document(document.path, newContent, document.version + 1)
    })

  def deleteAt(position: Int, amount: Int): F[Unit] =
    documentRef.update(document => {
      val newContent = position match {
        case p if p == 0 => document.content.substring(amount)
        case _ => document.content.substring(0, position) + document.content.substring(position + amount)
      }

      Document(document.path, newContent, document.version + 1)
    })

  def writeToFile: F[Unit] =
      for { document <- documentRef.get }
        yield Files.write(Paths.get(document.path), document.content.getBytes(StandardCharsets.UTF_8))

}

object DocumentHandler {

  def applyWithWrapper[F[_]: Async](documentRef: Ref[F,Document]): F[DocumentHandler[F]] =
    DocumentHandler(documentRef).pure[F]
}