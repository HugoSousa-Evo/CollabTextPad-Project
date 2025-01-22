package document

import scala.util.control.NoStackTrace

sealed trait FileError extends NoStackTrace
object FileError {
  final case object FileDoesNotExist   extends FileError
  final case object FileAlreadyExists extends FileError
}
