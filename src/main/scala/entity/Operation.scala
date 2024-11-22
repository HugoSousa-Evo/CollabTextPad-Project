package entity

import io.circe.generic.JsonCodec

@JsonCodec
sealed trait Operation {

  // Updates this operation based on another ->  used on operation queue to avoid document inconsistencies
  // Example: (insert, r, 1) and (insert, s, 1) will cause [0,1,2,3] document to become:
  // [0,r,s,1,2,...] on one client and [0,s,r,1,2,...] on the other

    def update(op: Operation): Operation = (this, op) match {

      case (curr: Operation.Insert, other: Operation.Insert) =>
        if(other.position <= curr.position){
          Operation.Insert(curr.position + other.content.length, curr.content, curr.sentBy)
        } else curr

      case (curr: Operation.Delete, other: Operation.Insert) => ???
      case (curr: Operation.Insert, other: Operation.Delete) => ???
      case (curr: Operation.Delete, other: Operation.Delete) => ???
    }
}

object Operation {

  def emptyInsert: Insert = Insert(0, "", 0)

  @JsonCodec
  final case class Insert(position: Int, content: String, sentBy: Int) extends Operation
  @JsonCodec
  final case class Delete(position: Int, amount: Int, sentBy: Int) extends Operation
}

