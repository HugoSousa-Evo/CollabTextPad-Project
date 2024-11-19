package entity

import io.circe.generic.JsonCodec

@JsonCodec
sealed trait Operation

object Operation {

  def emptyInsert: Insert = Insert(0, "", 0)

  @JsonCodec
  final case class Insert(position: Int, content: String, sentBy: Int) extends Operation
  @JsonCodec
  final case class Delete(position: Int, amount: Int, sentBy: Int) extends Operation
}