package io.quasar.cli

/** Mini-constructeur JSON (sans dépendance) pour les sorties `--json` stables. */
object Json:

  sealed trait J
  final case class JStr(s: String) extends J
  final case class JNum(d: Double) extends J
  final case class JBool(b: Boolean) extends J
  case object JNull extends J
  final case class JArr(items: List[J]) extends J
  final case class JObj(fields: List[(String, J)]) extends J

  def str(s: String): J = JStr(s)
  def num(d: Double): J = JNum(d)
  def int(i: Int): J = JNum(i.toDouble)
  def bool(b: Boolean): J = JBool(b)
  def arr(items: List[J]): J = JArr(items)
  def obj(fields: (String, J)*): J = JObj(fields.toList)
  def opt(o: Option[J]): J = o.getOrElse(JNull)

  def render(j: J): String = j match
    case JStr(s) => quote(s)
    case JNum(d) => if d == d.toLong.toDouble then d.toLong.toString else d.toString
    case JBool(b) => b.toString
    case JNull => "null"
    case JArr(items) => items.map(render).mkString("[", ",", "]")
    case JObj(fs) => fs.map((k, v) => s"${quote(k)}:${render(v)}").mkString("{", ",", "}")

  private def quote(s: String): String =
    val sb = StringBuilder("\"")
    s.foreach {
      case '"' => sb ++= "\\\""
      case '\\' => sb ++= "\\\\"
      case '\n' => sb ++= "\\n"
      case '\t' => sb ++= "\\t"
      case '\r' => sb ++= "\\r"
      case c => sb += c
    }
    sb ++= "\""
    sb.toString
