package io.quasar.analysis

import io.quasar.core.glc.LocalCausalityGraph
import io.quasar.core.ir.*

/** Mutation : fige un automate à un niveau (gain/perte de fonction). */
final case class Mutation(target: LocalState, makesReachable: Boolean):
  override def toString: String =
    s"${target.automaton} := ${target.level} ⇒ ${
        if makesReachable then "atteignable" else "inatteignable"
      }"

/**
 * Analyses d'intervention orientées-but (§7.2) : ensembles de coupe et mutations.
 */
object Intervention:

  /**
   * Ensembles de coupe minimaux : ensembles d'états locaux dont le blocage rend le but
   * **OA-inatteignable** (condition nécessaire ⇒ blocage garanti efficace). Recherche bornée par
   * `maxSize` sur les états locaux intermédiaires du GLC.
   */
  def cutsets(
      net: AutomataNetwork,
      ctx: Context,
      goal: LocalState,
      maxSize: Int = 3
  ): List[Set[LocalState]] =
    val glc = LocalCausalityGraph.build(net, ctx, goal)
    val initial =
      net.ordered.flatMap(au => ctx.levelsOf(au.name, au.states).map(LocalState(au.name, _))).toSet
    val candidates = (glc.objectives - goal -- initial).toList.sortBy(_.toString)

    def cutsGoal(blocked: Set[LocalState]): Boolean =
      !Reachability.mayReachBlocked(net, ctx, blocked).contains(goal)

    val found = scala.collection.mutable.ListBuffer.empty[Set[LocalState]]
    for size <- 1 to math.min(maxSize, candidates.size) do
      for combo <- candidates.combinations(size) do
        val s = combo.toSet
        // minimalité : ne pas inclure de sur-ensemble d'un cutset déjà trouvé
        if !found.exists(_.subsetOf(s)) && cutsGoal(s) then found += s
    found.toList

  /**
   * Mutations (gain/perte de fonction) modifiant l'atteignabilité du but.
   *
   *   - `enable` : le but, initialement non garanti atteignable, le devient (UA).
   *   - `disable` : le but, initialement atteignable (OA), devient inatteignable.
   *
   * Une mutation fige un automate `a` au niveau `k` (suppression de ses transitions + contexte
   * forcé à `a=k`).
   */
  def mutations(
      net: AutomataNetwork,
      ctx: Context,
      goal: LocalState,
      enable: Boolean
  ): List[Mutation] =
    val base = Reachability.analyze(net, ctx, goal)

    val results =
      for
        au <- net.ordered.toList
        k <- au.states.toList
      yield
        val frozen = au.copy(transitions = Nil)
        val mutNet = net.copy(automata = net.automata.updated(au.name, frozen))
        val mutCtx = ctx.withState(au.name, k)
        val r = Reachability.analyze(mutNet, mutCtx, goal)
        (LocalState(au.name, k), r)

    if enable then
      // n'a de sens que si le but n'est pas déjà garanti atteignable
      results.collect {
        case (ls, r) if r.uaReachable && !base.uaReachable => Mutation(ls, true)
      }
    else
      results.collect {
        case (ls, r) if !r.oaReachable && base.oaReachable => Mutation(ls, false)
      }
