package entity

import io.circe.generic.JsonCodec

@JsonCodec
case class User (name: String, memberOf: Set[String])
