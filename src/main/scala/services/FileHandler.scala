package services

import cats.effect.kernel.{Async, Ref}
import cats.implicits._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

case class FileHandler[F[_]: Async](path: String, ref: Ref[F, String]) {

  def fileInsert(position: Int, content: String): F[Unit] =
      ref.update(lines => position match {
        case p if p == 0 => content + lines
        case p if p >= lines.length => lines + content
        case _ => lines.substring(0, position) + content + lines.substring(position)
      }) *> println("insert").pure[F]


  def fileDelete(position: Int, amount: Int): F[Unit] =
      ref.update(lines => position match {
        case p if p == 0 => lines.substring(p + amount)
        case _ => lines.substring(0, position) + lines.substring(position + amount)
      }) *> println("delete").pure[F]

  def writeToFile: F[Unit] =
      for {
        text <- ref.get
        _ <- println(s"writing: $text").pure[F]
        _ <- Files.write(Paths.get(path), text.getBytes(StandardCharsets.UTF_8)).pure[F]
      } yield ()
}
