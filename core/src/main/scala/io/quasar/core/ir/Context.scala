package io.quasar.core.ir

/**
 * Contexte : ensemble des états locaux initialement possibles pour chaque automate. Un contexte non
 * déterministe associe à un automate un sous-ensemble de `S(a)`. L'absence d'un automate signifie «
 * tout état possible ».
 *
 * Sert de point de départ `--from` aux analyses d'atteignabilité.
 */
final case class Context(states: Map[String, Set[Int]]):

  /** Niveaux possibles de `automaton` (tout `S(a)` si non contraint). */
  def levelsOf(automaton: String, default: Range): Set[Int] =
    states.getOrElse(automaton, default.toSet)

  /** Vrai si `ls` est compatible avec ce contexte. */
  def admits(ls: LocalState): Boolean =
    states.get(ls.automaton).forall(_.contains(ls.level))

  /** Restreint l'automate `a` au singleton `{level}`. */
  def withState(a: String, level: Int): Context =
    Context(states.updated(a, Set(level)))

object Context:
  /** Contexte vide : aucun automate contraint (tout est possible). */
  val empty: Context = Context(Map.empty)

  /**
   * Parse `"a=0,b=1"` en contexte déterministe. Le mot-clé `default` (ou chaîne vide) donne le
   * contexte vide.
   */
  def parse(s: String): Either[String, Context] =
    val trimmed = s.trim
    if trimmed.isEmpty || trimmed == "default" then Right(empty)
    else
      val parts = trimmed.split(",").toList.map(_.trim).filter(_.nonEmpty)
      val parsed = parts.map(LocalState.parse)
      parsed.collectFirst { case Left(e) => e } match
        case Some(err) => Left(err)
        case None =>
          val ls = parsed.collect { case Right(v) => v }
          Right(Context(ls.groupMapReduce(_.automaton)(l => Set(l.level))(_ ++ _)))
