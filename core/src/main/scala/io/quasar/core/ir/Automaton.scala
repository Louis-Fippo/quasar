package io.quasar.core.ir

/**
 * Automate fini : un composant du réseau, avec ses états locaux `S(a) = {0..levels-1}` et ses
 * transitions locales.
 */
final case class Automaton(
    name: String,
    levels: Int,
    transitions: List[Transition] = Nil
):
  require(levels >= 1, s"l'automate '$name' doit avoir au moins 1 état local")

  /** Ensemble des états locaux `S(a)`. */
  def states: Range = 0 until levels

  /** Vrai si `level` est un état local valide de cet automate. */
  def hasLevel(level: Int): Boolean = level >= 0 && level < levels

  /** Transitions sortant de l'état local `level`. */
  def outgoing(level: Int): List[Transition] = transitions.filter(_.from == level)

  /** Transitions entrant dans l'état local `level`. */
  def incoming(level: Int): List[Transition] = transitions.filter(_.to == level)
