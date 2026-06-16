package io.quasar.io

import io.quasar.core.ir.*

import scala.annotation.tailrec

/**
 * Format texte canonique ANX de QUASAR (importeur + exporteur).
 *
 * Grammaire (une déclaration par ligne, `#` = commentaire) :
 * {{{
 *   model "nom"                         # optionnel
 *   automaton a [0, 1, 2]              # déclaration : a, niveaux 0..2
 *   a: 0 -> 1 when b=1 and c=0 @ 1.5   # transition + taux exponentiel
 *   a: 1 -> 0 @ erlang(2, 1.0)        # distribution Erlang
 *   a: 0 -> 1 @ phase(1.0, 2.0)       # distribution phase-type
 *   init a=0, b=1                      # contexte initial (métadonnée)
 * }}}
 * Sans `@`, la distribution par défaut (exponentielle, taux 1) est utilisée.
 */
object AnxFormat:

  // --- Import --------------------------------------------------------------

  def parse(text: String): IoResult[AutomataNetwork] =
    val lines = text.linesIterator.zipWithIndex.toList
    val state = ParseState()
    parseLines(lines, state).map(_.build())

  private final class ParseState:
    var modelName: Option[String] = None
    var levels: Map[String, Int] = Map.empty
    var transitions: Map[String, List[Transition]] = Map.empty.withDefaultValue(Nil)
    var init: Option[Context] = None

    def addTransition(t: Transition): Unit =
      transitions = transitions.updated(t.automaton, transitions(t.automaton) :+ t)

    def build(): AutomataNetwork =
      val automata = levels.map { (name, n) =>
        name -> Automaton(name, n, transitions.getOrElse(name, Nil))
      }
      val meta = Metadata(name = modelName, source = Some("anx"), initial = init)
      AutomataNetwork(automata, meta)

  @tailrec
  private def parseLines(
      lines: List[(String, Int)],
      st: ParseState
  ): IoResult[ParseState] =
    lines match
      case Nil => Right(st)
      case (raw, idx) :: rest =>
        val line = stripComment(raw).trim
        if line.isEmpty then parseLines(rest, st)
        else
          parseLine(line, idx + 1, st) match
            case Left(e) => Left(e)
            case Right(_) => parseLines(rest, st)

  private def stripComment(s: String): String =
    val h = s.indexOf('#')
    if h >= 0 then s.substring(0, h) else s

  private val automatonRe = """automaton\s+(\S+)\s*\[([^\]]*)\]""".r
  private val modelRe = """model\s+"?([^"]*)"?""".r
  private val initRe = """init\s+(.*)""".r
  // a: 0 -> 1 [when ...] [@ dist]
  private val transRe =
    """(\S+)\s*:\s*(\d+)\s*->\s*(\d+)\s*(?:when\s+(.*?))?\s*(?:@\s*(.*))?""".r

  private def parseLine(line: String, ln: Int, st: ParseState): IoResult[Unit] =
    line match
      case modelRe(n) if line.startsWith("model") =>
        st.modelName = Some(n.trim); Right(())

      case automatonRe(name, body) =>
        parseLevels(body, ln).map { n =>
          st.levels = st.levels.updated(name, n)
        }

      case initRe(ctx) =>
        Context.parse(ctx).left.map(e => IoError(e, Some(ln))).map(c => st.init = Some(c))

      case transRe(a, from, to, when, dist) =>
        for
          conds <- parseConditions(Option(when), ln)
          d <- parseDist(Option(dist), ln)
        yield st.addTransition(Transition(a, from.toInt, to.toInt, conds, d))

      case _ =>
        Left(IoError(s"déclaration non reconnue : '$line'", Some(ln)))

  /** `[0, 1, 2]` -> 3 niveaux ; accepte aussi `[3]` (nombre de niveaux). */
  private def parseLevels(body: String, ln: Int): IoResult[Int] =
    val parts = body.split(",").map(_.trim).filter(_.nonEmpty)
    if parts.isEmpty then Left(IoError("automate sans niveaux", Some(ln)))
    else
      val ints = parts.map(_.toIntOption)
      if ints.exists(_.isEmpty) then Left(IoError(s"niveaux non entiers : [$body]", Some(ln)))
      else
        val vals = ints.flatten
        // [0,1,2] = énumération -> max+1 niveaux ; [3] = nombre de niveaux
        if vals.length == 1 && vals.head > 0 && !body.contains("0") then Right(vals.head)
        else Right(vals.max + 1)

  private def parseConditions(when: Option[String], ln: Int): IoResult[List[LocalState]] =
    when.map(_.trim).filter(_.nonEmpty) match
      case None => Right(Nil)
      case Some(w) =>
        val atoms = w.split("(?i)\\s+and\\s+").map(_.trim).filter(_.nonEmpty).toList
        val parsed = atoms.map(LocalState.parse)
        parsed.collectFirst { case Left(e) => e } match
          case Some(e) => Left(IoError(e, Some(ln)))
          case None => Right(parsed.collect { case Right(v) => v })

  private val erlangRe = """erlang\(\s*(\d+)\s*,\s*([0-9.eE+-]+)\s*\)""".r
  private val phaseRe = """phase\(\s*(.*)\s*\)""".r

  private def parseDist(dist: Option[String], ln: Int): IoResult[Distribution] =
    dist.map(_.trim).filter(_.nonEmpty) match
      case None => Right(Distribution.default)
      case Some(d) =>
        d match
          case erlangRe(k, r) =>
            r.toDoubleOption
              .toRight(IoError(s"taux Erlang invalide : $r", Some(ln)))
              .map(Distribution.Erlang(k.toInt, _))
          case phaseRe(rs) =>
            val parsed = rs.split(",").map(_.trim).filter(_.nonEmpty).map(_.toDoubleOption)
            if parsed.exists(_.isEmpty) then
              Left(IoError(s"taux phase-type invalides : $rs", Some(ln)))
            else Right(Distribution.PhaseType(parsed.flatten.toVector))
          case _ =>
            d.toDoubleOption
              .toRight(IoError(s"distribution non reconnue : '$d'", Some(ln)))
              .map(Distribution.Exponential(_))

  // --- Export --------------------------------------------------------------

  def render(net: AutomataNetwork): String =
    val sb = StringBuilder()
    sb ++= s"# QUASAR ANX model\n"
    net.metadata.name.foreach(n => sb ++= s"""model "$n"\n""")
    sb ++= "\n"
    for au <- net.ordered do
      val states = au.states.mkString(", ")
      sb ++= s"automaton ${au.name} [$states]\n"
    sb ++= "\n"
    for au <- net.ordered; t <- au.transitions do
      sb ++= renderTransition(t)
      sb ++= "\n"
    net.metadata.initial.foreach { ctx =>
      val inits = ctx.states.toList.sortBy(_._1).flatMap { (a, ls) =>
        ls.toList.sorted.map(l => s"$a=$l")
      }
      if inits.nonEmpty then sb ++= s"\ninit ${inits.mkString(", ")}\n"
    }
    sb.toString

  private def renderTransition(t: Transition): String =
    val cond = if t.conditions.isEmpty then "" else t.conditions.mkString(" when ", " and ", "")
    s"${t.automaton}: ${t.from} -> ${t.to}$cond @ ${renderDist(t.dist)}"

  private def renderDist(d: Distribution): String = d match
    case Distribution.Exponential(r) => r.toString
    case Distribution.Erlang(k, r) => s"erlang($k, $r)"
    case Distribution.PhaseType(rs) => s"phase(${rs.mkString(", ")})"
