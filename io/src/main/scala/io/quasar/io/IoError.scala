package io.quasar.io

/** Erreur d'import / export (parsing, format, I/O fichier). */
final case class IoError(message: String, line: Option[Int] = None):
  override def toString: String =
    line match
      case Some(n) => s"ligne $n : $message"
      case None => message

type IoResult[A] = Either[IoError, A]
