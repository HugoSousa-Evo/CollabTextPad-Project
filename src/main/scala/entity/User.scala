package entity

import io.circe.generic.JsonCodec

@JsonCodec
case class User (name: String, ownerOf: Set[String], memberOf: Set[String])
