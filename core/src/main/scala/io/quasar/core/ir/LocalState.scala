package io.quasar.core.ir

/**
 * État local `a=i` : l'automate `automaton` est au niveau `level`.
 *
 * C'est l'atome de base des préconditions de transition et des objectifs d'atteignabilité. Dans la
 * notation de la thèse : `ς` est un état local.
 */
final case class LocalState(automaton: String, level: Int):
  override def toString: String = s"$automaton=$level"

object LocalState:
  /** Parse `"a=3"` en `LocalState("a", 3)`. */
  def parse(s: String): Either[String, LocalState] =
    s.split("=", 2) match
      case Array(a, lvl) =>
        a.trim.toIntOption match
          case Some(_) => Left(s"nom d'automate invalide dans '$s'")
          case None =>
            lvl.trim.toIntOption.toRight(s"niveau non entier dans '$s'").map(LocalState(a.trim, _))
      case _ => Left(s"état local mal formé '$s' (attendu 'a=i')")
