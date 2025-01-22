package server

import com.comcast.ip4s.{Host, Port}
import pureconfig.ConfigReader

case class ServerConfig (serverPort: Port, serverHost: Host)