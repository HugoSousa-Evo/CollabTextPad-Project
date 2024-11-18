package entity

import io.circe.generic.JsonCodec

@JsonCodec
sealed trait Operation

object Operation{

  def emptyInsert: Insert = Insert(0, "")

  @JsonCodec
  final case class Insert(position: Int, content: String) extends Operation
  @JsonCodec
  final case class Delete(position: Int, amount: Int) extends Operation
}