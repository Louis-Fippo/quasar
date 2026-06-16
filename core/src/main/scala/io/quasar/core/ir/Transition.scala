package io.quasar.core.ir

/**
 * Transition locale d'un automate : `automaton: from -> to when conditions`.
 *
 * `conditions` est l'ensemble des états locaux (d'autres automates, ou du même) requis pour que la
 * transition soit tirable. `dist` est la distribution de délai (exponentielle par défaut, D4).
 *
 * Notation thèse : un arc local valué de `⌈Gω_ς⌉`.
 */
final case class Transition(
    automaton: String,
    from: Int,
    to: Int,
    conditions: List[LocalState] = Nil,
    dist: Distribution = Distribution.default
):
  /** L'état local source `automaton=from`. */
  def source: LocalState = LocalState(automaton, from)

  /** L'état local cible `automaton=to`. */
  def target: LocalState = LocalState(automaton, to)

  /** Taux effectif de la transition (cf. [[Distribution.meanRate]]). */
  def rate: Double = dist.meanRate

  override def toString: String =
    val cond = if conditions.isEmpty then "" else conditions.mkString(" when ", " and ", "")
    s"$automaton: $from -> $to$cond"
