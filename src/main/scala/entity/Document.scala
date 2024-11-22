package entity

//case class Document(content: String, version: Int, cursorPos: Int, clientCursors: List[(Int, Int)], undoStack: Seq[Operation])

case class Document(path: String, content: String, version: Int)