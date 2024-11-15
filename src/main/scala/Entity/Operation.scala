package Entity

import io.circe.generic.JsonCodec


sealed trait Operation

object Operation{

  @JsonCodec
  final case class Insert(position: Int, content: String, version: Int) extends Operation
  @JsonCodec
  case class Delete(position: Int, amount: Int, version: Int) extends Operation
}