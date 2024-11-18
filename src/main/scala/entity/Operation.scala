package entity

import io.circe.generic.JsonCodec


sealed trait Operation

object Operation{

  def emptyInsert: Insert = Insert(0, "")

  @JsonCodec
  final case class Insert(position: Int, content: String) extends Operation
  @JsonCodec
  case class Delete(position: Int, amount: Int) extends Operation
}