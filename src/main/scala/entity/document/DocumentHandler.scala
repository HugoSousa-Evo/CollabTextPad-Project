package entity.document

import cats.effect.implicits.{effectResourceOps, genSpawnOps}
import cats.effect.kernel.{Async, Ref, Resource}


import cats.effect.std.AtomicCell
import cats.effect.{Ref, Temporal, Concurrent, Fiber}
import cats.syntax.all._
import cats.effect.syntax.all._
import fs2.io.file.Files

import scala.concurrent.duration.FiniteDuration

trait DocumentHandler[F[_]] {

  def open(documentPath: String): F[Unit]

  def unsubscribe(documentPath: String): F[Unit]

  def insertAt(documentPath: String, position: Int, content: String): F[Unit]

  def deleteAt(documentPath: String, position: Int, amount: Int): F[Unit]
}

object DocumentHandler {

  private type DocumentPath = String
  private case class DocumentWrapper[F[_]](
                                    document: Document,
                                    subscribers: Int,
                                    autoSaveFiber: Fiber[F, Throwable, Unit]
                                  )

  def of[F[_]: Temporal: Files](
                                 autoSaveRate: FiniteDuration
                               ): F[DocumentHandler[F]] =
    for {

      documentsRef <- AtomicCell[F].of( Map.empty[DocumentPath, DocumentWrapper[F]] )

    } yield new DocumentHandler[F] {

      def open(documentPath: DocumentPath): F[Unit] =
        documentsRef.evalUpdate { documents =>
          documents.get(documentPath) match {
            case Some(documentWrapper) =>
              val newDocumentWrapper =
                documentWrapper.copy(
                  subscribers = documentWrapper.subscribers + 1
                )
              documents
                .updated(documentPath, newDocumentWrapper)
                .pure[F]

            case None =>
              val fs2Path = fs2.io.file.Path(documentPath)

              for {
                // read file contents
                content <- fs2.io.file
                  .Files[F]
                  .readAll(fs2Path)
                  .through(fs2.text.utf8.decode)
                  .compile
                  .string

                // auto save file every some seconds
                fiber <- fs2.Stream
                  .awakeEvery(autoSaveRate)
                  .evalMap { _ =>
                    for {
                      documents <- documentsRef.get
                      _ <- documents.get(documentPath).traverse_ {
                        documentWithSubscribers =>
                          fs2.Stream
                            .emit(documentWithSubscribers.document.content)
                            .through(fs2.text.lines)
                            .map(_ + "\n")
                            .through(fs2.text.utf8.encode)
                            .through(fs2.io.file.Files[F].writeAll(fs2Path))
                            .compile
                            .drain
                      }
                    } yield ()
                  }
                  .compile
                  .drain
                  .start

                documentWrapper = DocumentWrapper(
                  document = Document(documentPath, content, version = 0),
                  subscribers = 1,
                  autoSaveFiber = fiber
                )
              } yield documents.updated(documentPath, documentWrapper)
          }
        }

      def unsubscribe(documentPath: DocumentPath): F[Unit] =
        documentsRef.evalUpdate { documents =>
          documents.get(documentPath) match {
            case Some(documentWrapper) =>
              val newSubscribers = documentWrapper.subscribers - 1

              if (newSubscribers <= 0) {
                for {
                  _ <- documentWrapper.autoSaveFiber.cancel
                } yield documents.removed(documentPath)
              } else {
                val newDocumentWrapper = documentWrapper.copy(
                  subscribers = newSubscribers
                )
                documents.updated(documentPath, newDocumentWrapper).pure[F]
              }

            case None => documents.pure[F]
          }
        }

      def insertAt(
                    documentPath: DocumentPath,
                    position: Int,
                    content: String
                  ): F[Unit] =
        documentsRef.evalUpdate { documents =>
          documents.get(documentPath) match {
            case Some(wrapper) =>
              val document = wrapper.document
              val newContent = position match {
                case p if p == 0 => content + document.content
                case p if p >= document.content.length => document.content + content
                case _ => document.content.substring(0, position) + content + document.content.substring(position)
              }

              documents.updated(documentPath, wrapper.copy(document = document.copy(content = newContent))).pure[F]

            case None => documents.pure[F]
          }
        }

      def deleteAt(
                    documentPath: DocumentPath,
                    position: Int,
                    amount: Int
                  ): F[Unit] =
        documentsRef.evalUpdate { documents =>
          documents.get(documentPath) match {
            case Some(wrapper) =>
              val document = wrapper.document
              val newContent = position match {
                case p if p == 0 => document.content.substring(amount)
                case _ => document.content.substring(0, position) + document.content.substring(position + amount)
              }

              documents.updated(documentPath, wrapper.copy(document = document.copy(content = newContent))).pure[F]

            case None => documents.pure[F]
          }
        }
    }
}

//class DocumentHandler[F[_]: Async] private (documentRef: Ref[F,Document]) {
//
//  def docPath: F[String] = for { document <- documentRef.get } yield document.path
//
//  def insertAt(position: Int, content: String): F[Unit] =
//    documentRef.update(document => {
//
//      val newContent = position match {
//        case p if p == 0 => content + document.content
//        case p if p >= document.content.length => document.content + content
//        case _ => document.content.substring(0, position) + content + document.content.substring(position)
//      }
//
//      Document(document.path, newContent, document.version + 1)
//    })
//
//  def deleteAt(position: Int, amount: Int): F[Unit] =
//    documentRef.update(document => {
//      val newContent = position match {
//        case p if p == 0 => document.content.substring(amount)
//        case _ => document.content.substring(0, position) + document.content.substring(position + amount)
//      }
//
//      Document(document.path, newContent, document.version + 1)
//    })
//}
//
//object DocumentHandler {
//
//  def make[F[_]: fs2.io.file.Files: Async](user: String, filename: String, autoSaveRate: FiniteDuration): Resource[F, DocumentHandler[F]] = {
//
//    val fs2Path = fs2.io.file.Path(s"./Documents/$user/$filename")
//
//    for {
//      // read file contents
//      content <- fs2.io.file.Files[F]
//        .readAll(fs2Path)
//        .through(fs2.text.utf8.decode)
//        .compile.string.toResource
//
//      // create reference to the current document
//      documentRef <- Ref.of[F, Document](Document(filename, content, version = 1)).toResource
//
//      // auto save file every some seconds
//      _ <- fs2.Stream.awakeEvery(autoSaveRate).evalMap { _ =>
//        for {
//          document <- documentRef.get
//          _ <- fs2.Stream.emit(document.content)
//            .through(fs2.text.lines).map(_ + "\n")
//            .through(fs2.text.utf8.encode)
//            .through(fs2.io.file.Files[F].writeAll(fs2Path))
//            .compile.drain
//        } yield ()
//      }.compile.drain.background
//
//    } yield new DocumentHandler(documentRef)
//  }
// }