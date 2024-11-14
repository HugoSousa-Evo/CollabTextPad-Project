package Entity

sealed trait Operation {}

case class Insert(position: Int, content: String, version: Int) extends Operation
case class Delete(position: Int, amount: Int, version: Int) extends Operation