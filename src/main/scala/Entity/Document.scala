package Entity

case class Document(content: String, version: Int, cursorPos: Int, clientCursors: List[(Int, Int)], undoStack: Seq[Operation])