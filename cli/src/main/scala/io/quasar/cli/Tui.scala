package io.quasar.cli

import com.monovore.decline.*
import org.jline.reader.{EndOfFileException, LineReaderBuilder, UserInterruptException}
import org.jline.terminal.TerminalBuilder

/**
 * Mode interactif `quasar tui` (§7.10).
 *
 * Couche d'orchestration au-dessus des mêmes commandes que la CLI (aucune logique dupliquée) :
 * chaque ligne saisie est redispatchée vers [[Main.execute]]. Un « modèle courant » optionnel est
 * préfixé automatiquement aux commandes d'analyse pour fluidifier l'exploration.
 */
object Tui:

  type Run = () => Int

  val command: Opts[Run] =
    Opts.argument[String]("model").orNone.map(initial => () => run(initial))

  private val banner =
    """QUASAR TUI — tapez une commande (sans le préfixe « quasar »).
      |  help                      cette aide
      |  use <model>               définit le modèle courant
      |  info | inspect | stats    sur le modèle courant
      |  analyze ... | topology ...  commandes complètes
      |  exit / quit               quitter""".stripMargin

  private val groupNames =
    Set("model", "analyze", "topology", "transform", "solver", "verify", "bench", "repo")

  // commandes qui prennent un <model> en 1er argument (pour l'auto-préfixe)
  private val modelFirst =
    Set(
      "info",
      "inspect",
      "stats",
      "validate",
      "reachability",
      "quantitative",
      "probability",
      "delay",
      "scenario",
      "cutsets",
      "mutations",
      "compare",
      "scc",
      "cycles",
      "feedback",
      "fixpoints",
      "attractors",
      "trap-spaces",
      "reduce",
      "slice"
    )

  def run(initialModel: Option[String]): Int =
    val terminal = TerminalBuilder.builder().system(true).build()
    val reader = LineReaderBuilder.builder().terminal(terminal).build()
    var current = initialModel
    Console.out(banner)
    current.foreach(m => Console.out(s"modèle courant : $m"))

    var running = true
    while running do
      val line =
        try reader.readLine("quasar> ")
        catch
          case _: UserInterruptException => ""
          case _: EndOfFileException => null
      if line == null then running = false
      else
        val tokens = tokenize(line.trim)
        tokens match
          case Nil => ()
          case "exit" :: _ | "quit" :: _ => running = false
          case "help" :: _ => Console.out(banner)
          case "use" :: m :: _ => current = Some(m); Console.out(s"modèle courant : $m")
          case cmd :: rest => dispatch(cmd, rest, current)
    0

  private def dispatch(cmd: String, rest: List[String], current: Option[String]): Unit =
    val args =
      if groupNames.contains(cmd) then
        // commande de groupe complète : insère le modèle courant si absent et attendu
        rest.headOption match
          case Some(sub)
              if modelFirst.contains(sub) && !rest.lift(1).exists(noDash) && current.isDefined =>
            cmd :: sub :: current.get :: rest.drop(1)
          case _ => cmd :: rest
      else if modelFirst.contains(cmd) && current.isDefined && !rest.headOption.exists(noDash) then
        // raccourci : « info » -> « model info <courant> » / « analyze <cmd> <courant> »
        groupOf(cmd) match
          case Some(g) => g :: cmd :: current.get :: rest
          case None => cmd :: rest
      else cmd :: rest
    try
      val code = Main.execute(args)
      if code != 0 then Console.err(s"(code $code)")
    catch case e: Exception => Console.err(s"erreur : ${e.getMessage}")

  private def noDash(s: String): Boolean = s.startsWith("-")

  private def groupOf(sub: String): Option[String] =
    if Set("info", "inspect", "stats", "validate").contains(sub) then Some("model")
    else if Set("scc", "cycles", "feedback", "fixpoints", "attractors", "trap-spaces").contains(sub)
    then Some("topology")
    else if Set("reduce", "slice").contains(sub) then Some("transform")
    else Some("analyze")

  /** Découpage en jetons respectant les guillemets simples/doubles. */
  private def tokenize(s: String): List[String] =
    val out = List.newBuilder[String]
    val cur = StringBuilder()
    var quote: Char = 0
    for c <- s do
      if quote != 0 then if c == quote then quote = 0 else cur += c
      else
        c match
          case '"' | '\'' => quote = c
          case w if w.isWhitespace =>
            if cur.nonEmpty then { out += cur.toString; cur.clear() }
          case _ => cur += c
    if cur.nonEmpty then out += cur.toString
    out.result()
