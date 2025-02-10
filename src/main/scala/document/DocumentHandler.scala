package document

import cats.effect.implicits.genSpawnOps
import cats.effect.std.AtomicCell
import cats.effect.{Temporal,Fiber}
import cats.syntax.all._
import fs2.concurrent.Topic
import fs2.io.file.Files

import scala.concurrent.duration.FiniteDuration

trait DocumentHandler[F[_]] {

  // Opens a new document session, returning the outgoing stream of operations
  // to send to the users currently in the document
  def open(documentPath: String): F[fs2.Stream[F, Operation]]

  // Removes a user from the session on Websocket disconnect,
  // closing the session if there are no more active users
  def unsubscribe(documentPath: String): F[Unit]

  // Executes the method related to the respective received operation
  def handle(documentPath: String, operation: Operation): F[Unit]

  def getSubscribers(documentPath: String): F[Int]
}

object DocumentHandler {

  private type DocumentPath = String
  // Class that represents a currently open document
  private case class DocumentSession[F[_]](
                                    document: Document,
                                    subscribers: Int,
                                    autoSaveFiber: Fiber[F, Throwable, Unit],
                                    topic: Topic[F, Operation]
                                  )

  def of[F[_]: Temporal: Files]
  (autoSaveRate: FiniteDuration): F[DocumentHandler[F]] =
    for {
      // AtomicCell vs Ref : https://stackoverflow.com/questions/78872562/whats-the-difference-between-ref-and-atomiccell-of-cats-effect-3
      // Creates a map to track which documents are currently open
      documentsRef <- AtomicCell[F].of( Map.empty[DocumentPath, DocumentSession[F]] )

    } yield new DocumentHandler[F] {

      def open(documentPath: DocumentPath): F[fs2.Stream[F, Operation]] =
        documentsRef.evalModify { documents =>
          documents.get(documentPath) match {

            // if session already exists, increase subscriber count and subscribe the new user to the topic
            case Some(documentWrapper) =>
              val newDocumentWrapper = {
                documentWrapper.copy(
                  subscribers = documentWrapper.subscribers + 1
                )
              }
              (documents.updated(documentPath, newDocumentWrapper), documentWrapper.topic.subscribe(100)).pure[F]

            // if there isn't an open session of this document
            case None =>
              val fs2Path = fs2.io.file.Path(documentPath)

              for {
                // read current file contents
                content <- fs2.io.file
                  .Files[F]
                  .readAll(fs2Path)
                  .through(fs2.text.utf8.decode)
                  .compile
                  .string

                // create new topic to stream the executed operations to the other users
                topic <- Topic[F, Operation]

                // auto save contents to file every some seconds
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
                            .map(_ + "\n" )
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

                // opens a new session
                documentWrapper = DocumentSession(
                  document = Document(documentPath, content, version = 0),
                  subscribers = 1,
                  autoSaveFiber = fiber,
                  topic = topic
                )
                // adds this session to the map of document sessions and returns the topic
              } yield (documents.updated(documentPath, documentWrapper), topic.subscribe(100))
          }
        }

      def unsubscribe(documentPath: DocumentPath): F[Unit] =
        documentsRef.evalUpdate { documents =>
          documents.get(documentPath) match {
            case Some(documentWrapper) =>
              val newSubscribers = documentWrapper.subscribers - 1

              // If session has no more users, cancel the autosave and remove session from the map
              // Else just update the subscriber amount of the current session
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

            // Should never happen, but just in case, updates the map without changes
            case None => documents.pure[F]
          }
        }

      def getSubscribers(documentPath: DocumentPath): F[Int] =
        for {
          documents <- documentsRef.get
        } yield documents.get(documentPath) match {
          case Some(session) => session.subscribers
          case None => 0
        }

      def handle(documentPath: DocumentPath, operation: Operation): F[Unit] = {
        operation match {
          case o: Operation.Insert => insertAt(documentPath, o)
          case o: Operation.Delete => deleteAt(documentPath, o)
          case Operation.Close() => sendClose(documentPath)
        }
      }


      // --- OPERATIONS --- ( possibly could be moved to some other file )
      private def insertAt
      (documentPath: DocumentPath, operation: Operation.Insert): F[Unit] =
        documentsRef.evalUpdate { documents =>
          documents.get(documentPath) match {
            case Some(wrapper) =>
              val document = wrapper.document
              val newContent = operation.position match {
                case p if p == 0 => operation.content + document.content
                case p if p >= document.content.length => document.content + operation.content
                case _ => document.content.substring(0, operation.position) + operation.content + document.content.substring(operation.position)
              }
              wrapper.topic.publish1(operation) as
                documents.updated(documentPath, wrapper.copy(document = document.copy(content = newContent)))

            case None => documents.pure[F]
          }
        }

      private def deleteAt
      (documentPath: DocumentPath, operation: Operation.Delete): F[Unit] =
        documentsRef.evalUpdate { documents =>
          documents.get(documentPath) match {
            case Some(wrapper) =>
              val document = wrapper.document
              val newContent = operation.position match {
                case p if p == 0 => document.content.substring(operation.amount)
                case _ => document.content.substring(0, operation.position) + document.content.substring(operation.position + operation.amount)
              }
              wrapper.topic.publish1(operation) as
                documents.updated(documentPath, wrapper.copy(document = document.copy(content = newContent)))

            case None => documents.pure[F]
          }
        }

      private def sendClose
      (documentPath: DocumentPath): F[Unit] =
        documentsRef.evalUpdate { documents =>
          documents.get(documentPath) match {
            case Some(wrapper) =>
              wrapper.topic.publish1(Operation.Close()) as documents

            case None => documents.pure[F]
          }
        }
    }
}
