package entity.document

import scala.util.control.NoStackTrace

sealed trait FileError extends NoStackTrace
object FileError {
  final case object InvalidFilePath          extends FileError
  final case object NegativeAutoSaveDuration extends FileError
  final case object FileAlreadyExistsForUser extends FileError
}
