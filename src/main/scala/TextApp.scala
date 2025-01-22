
import cats.effect.{ExitCode, IO, IOApp}
import server.Server


object TextApp extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = {
    
    for {

      _ <-   Server.server[IO]().useForever.void
    } yield ExitCode.Success
  }
  
}