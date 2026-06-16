package io.quasar.io

import io.quasar.core.ir.*

import scala.annotation.tailrec

/**
 * Format Automata Network de Pint (`.an`).
 *
 * Syntaxe supportée (noms entre guillemets ou nus) :
 * {{{
 *   "a" [0, 1, 2]
 *   "a" 0 -> 1 when "b"=1 and "c"=0
 *   "b" 1 -> 0
 *   initial_state "a"=0, "b"=1
 * }}}
 * Les commentaires Pint commencent par `--`. Un taux optionnel `@ <r>` est accepté en extension
 * (sinon distribution exponentielle par défaut).
 */
object AnFormat:

  def parse(text: String): IoResult[AutomataNetwork] =
    val lines = text.linesIterator.zipWithIndex.toList
    val st = ParseState()
    loop(lines, st).map(_.build())

  private final class ParseState:
    var levels: Map[String, Int] = Map.empty
    var transitions: Map[String, List[Transition]] = Map.empty.withDefaultValue(Nil)
    var init: Option[Context] = None

    def add(t: Transition): Unit =
      transitions = transitions.updated(t.automaton, transitions(t.automaton) :+ t)

    def build(): AutomataNetwork =
      val automata = levels.map((n, k) => n -> Automaton(n, k, transitions.getOrElse(n, Nil)))
      AutomataNetwork(automata, Metadata(source = Some("an"), initial = init))

  @tailrec
  private def loop(lines: List[(String, Int)], st: ParseState): IoResult[ParseState] =
    lines match
      case Nil => Right(st)
      case (raw, idx) :: rest =>
        val line = strip(raw).trim
        if line.isEmpty then loop(rest, st)
        else
          parseLine(line, idx + 1, st) match
            case Left(e) => Left(e)
            case Right(_) => loop(rest, st)

  private def strip(s: String): String =
    val h = s.indexOf("--")
    if h >= 0 then s.substring(0, h) else s

  private val name = """"?([\w]+)"?"""
  private val declRe = s"""$name\\s*\\[([^\\]]*)\\]""".r
  private val initRe = """initial_state\s+(.*)""".r
  private val transRe =
    s"""$name\\s+(\\d+)\\s*->\\s*(\\d+)\\s*(?:when\\s+(.*?))?\\s*(?:@\\s*([0-9.eE+-]+))?""".r
  private val condRe = s"""$name\\s*=\\s*(\\d+)""".r

  private def parseLine(line: String, ln: Int, st: ParseState): IoResult[Unit] =
    line match
      case initRe(rest) =>
        parseInit(rest, ln).map(c => st.init = Some(c))
      case declRe(n, body) =>
        parseLevels(body, ln).map(k => st.levels = st.levels.updated(n, k))
      case transRe(a, from, to, when, rate) =>
        for
          conds <- parseConds(Option(when), ln)
          dist = Option(rate)
            .flatMap(_.toDoubleOption)
            .map(Distribution.Exponential(_))
            .getOrElse(Distribution.default)
        yield st.add(Transition(a, from.toInt, to.toInt, conds, dist))
      case _ =>
        Left(IoError(s"déclaration .an non reconnue : '$line'", Some(ln)))

  private def parseLevels(body: String, ln: Int): IoResult[Int] =
    val parts = body.split(",").map(_.trim).filter(_.nonEmpty).map(_.toIntOption)
    if parts.isEmpty || parts.exists(_.isEmpty) then
      Left(IoError(s"niveaux invalides : [$body]", Some(ln)))
    else Right(parts.flatten.max + 1)

  private def parseConds(when: Option[String], ln: Int): IoResult[List[LocalState]] =
    when.map(_.trim).filter(_.nonEmpty) match
      case None => Right(Nil)
      case Some(w) =>
        val atoms = w.split("(?i)\\s+and\\s+").map(_.trim).filter(_.nonEmpty).toList
        val ls = atoms.map {
          case condRe(a, lvl) => Right(LocalState(a, lvl.toInt))
          case other => Left(IoError(s"précondition .an invalide : '$other'", Some(ln)))
        }
        ls.collectFirst { case Left(e) => e } match
          case Some(e) => Left(e)
          case None => Right(ls.collect { case Right(v) => v })

  private def parseInit(rest: String, ln: Int): IoResult[Context] =
    val atoms = rest.split(",").map(_.trim).filter(_.nonEmpty).toList
    val ls = atoms.map {
      case condRe(a, lvl) => Right(LocalState(a, lvl.toInt))
      case other => Left(IoError(s"initial_state invalide : '$other'", Some(ln)))
    }
    ls.collectFirst { case Left(e) => e } match
      case Some(e) => Left(e)
      case None =>
        val states = ls.collect { case Right(v) => v }
        Right(Context(states.groupMapReduce(_.automaton)(l => Set(l.level))(_ ++ _)))

  // --- export --------------------------------------------------------------

  def render(net: AutomataNetwork): String =
    val sb = StringBuilder()
    sb ++= "-- QUASAR export, Pint .an format\n"
    for au <- net.ordered do sb ++= s""""${au.name}" [${au.states.mkString(", ")}]\n"""
    sb ++= "\n"
    for au <- net.ordered; t <- au.transitions do
      val cond =
        if t.conditions.isEmpty then ""
        else
          t.conditions.map(c => s""""${c.automaton}"=${c.level}""").mkString(" when ", " and ", "")
      sb ++= s""""${t.automaton}" ${t.from} -> ${t.to}$cond @ ${t.rate}\n"""
    net.metadata.initial.foreach { ctx =>
      val inits = ctx.states.toList.sortBy(_._1).flatMap { (a, ls) =>
        ls.toList.sorted.map(l => s""""$a"=$l""")
      }
      if inits.nonEmpty then sb ++= s"\ninitial_state ${inits.mkString(", ")}\n"
    }
    sb.toString
