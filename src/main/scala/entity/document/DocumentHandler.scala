package entity.document

import cats.effect.implicits.{effectResourceOps, genSpawnOps}
import cats.effect.kernel.{Async, Ref, Resource}
import cats.implicits._

import scala.concurrent.duration.FiniteDuration

class DocumentHandler[F[_]: Async] private (documentRef: Ref[F,Document]) {

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
}

object DocumentHandler {

  def make[F[_]: fs2.io.file.Files: Async](user: String, filename: String, autoSaveRate: FiniteDuration): Resource[F, DocumentHandler[F]] = {

    val fs2Path = fs2.io.file.Path(s"./Documents/$user/$filename")

    for {
      // read file contents
      content <- fs2.io.file.Files[F]
        .readAll(fs2Path)
        .through(fs2.text.utf8.decode)
        .compile.string.toResource

      // create reference to the current document
      documentRef <- Ref.of[F, Document](Document(filename, content, version = 1)).toResource

      // auto save file every some seconds
      _ <- fs2.Stream.awakeEvery(autoSaveRate).evalMap { _ =>
        for {
          document <- documentRef.get
          _ <- fs2.Stream.emit(document.content)
            .through(fs2.text.lines).map(_ + "\n")
            .through(fs2.text.utf8.encode)
            .through(fs2.io.file.Files[F].writeAll(fs2Path))
            .compile.drain
        } yield ()
      }.compile.drain.background

    } yield new DocumentHandler(documentRef)
  }
}