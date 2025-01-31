package document

import io.circe.generic.JsonCodec
import io.circe.syntax.EncoderOps
import org.http4s.websocket.WebSocketFrame

@JsonCodec
sealed trait Operation {

  // Updates this operation based on another ->  used on operation queue to avoid document inconsistencies
  // Example: (insert, r, 1) and (insert, s, 1) will cause [0,1,2,3] document to become:
  // [0,r,s,1,2,...] on one client and [0,s,r,1,2,...] on the other

  def update(op: Operation): Operation

  def operationToTextFrame: WebSocketFrame.Text
}

object Operation {

  def emptyInsert: Insert = Insert(0, "", "")

  @JsonCodec
  final case class Insert(position: Int, content: String, sentBy: String) extends Operation {

    override def operationToTextFrame: WebSocketFrame.Text =
      WebSocketFrame.Text(identity(this).asJson.deepMerge(Map("type" -> "insert").asJson).toString)

    override def update(op: Operation): Operation = op match {

      case other: Insert =>
        if(other.position <= position){
          Operation.Insert(position + other.content.length, content, sentBy)
        }
        else identity(this)

      case other: Delete =>
        if(other.position < position){
          Operation.Insert(position - other.amount, content, sentBy)
        }
        else identity(this)
    }
  }

  @JsonCodec
  final case class Delete(position: Int, amount: Int, sentBy: String) extends Operation {

    override def operationToTextFrame: WebSocketFrame.Text =
      WebSocketFrame.Text(identity(this).asJson.deepMerge(Map("type" -> "delete").asJson).toString)

    override def update(op: Operation): Operation = op match {

      case other: Insert =>

        if(other.position <= position){
          Operation.Delete(position + other.content.length, amount, sentBy)
        }
        else identity(this)

      case other: Delete =>

        if(other.position <= position){
          // check if it overlaps
          if(position <= other.position + other.amount && position >= other.position ){

            Operation.Delete(position - (position - other.position) , amount, sentBy)

          } else Operation.Delete(position - other.amount , amount, sentBy)

        }
        else identity(this)
    }
  }
}