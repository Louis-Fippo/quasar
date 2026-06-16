package io.quasar.core.glc

import io.quasar.core.ir.*

/** Objectif du GLC : atteindre l'état local `target` depuis les niveaux `fromLevels`. */
final case class Objective(target: LocalState, fromLevels: Set[Int]):
  override def toString: String = s"⟨reach $target⟩"

/**
 * Solution d'un objectif : un chemin local qui réalise l'objectif. Ses préconditions deviennent des
 * sous-objectifs.
 */
final case class Solution(path: LocalPath):
  def requires: List[LocalState] = path.conditions
  def transitions: List[Transition] = path.steps

/**
 * Graphe de Causalité Locale (GLC) à la Pint/Paulevé.
 *
 * Graphe orienté objectif/solution : chaque objectif `a=j` est relié aux solutions (chemins locaux
 * dans `a`) qui le réalisent ; chaque solution est reliée aux sous-objectifs correspondant à ses
 * préconditions. La construction est orientée-but (uniquement les objectifs pertinents) et
 * mémoïsée.
 */
final case class LocalCausalityGraph(
    net: AutomataNetwork,
    context: Context,
    root: Objective,
    solutions: Map[LocalState, List[Solution]]
):

  /** Tous les états locaux (objectifs) apparaissant dans le graphe. */
  def objectives: Set[LocalState] = solutions.keySet

  /** Nombre total de solutions sur tous les objectifs. */
  def solutionCount: Int = solutions.values.map(_.size).sum

  /** Un objectif sans aucune solution est structurellement inatteignable. */
  def deadObjectives: Set[LocalState] = solutions.collect { case (k, Nil) => k }.toSet

object LocalCausalityGraph:

  /**
   * Construit le GLC orienté-but pour `goal` à partir de `context`.
   *
   * Pour chaque objectif `a=j`, les solutions sont les chemins locaux acycliques depuis les niveaux
   * initiaux possibles de `a` vers `j`. Les préconditions de chaque solution engendrent
   * récursivement de nouveaux objectifs (mémoïsés).
   */
  def build(
      net: AutomataNetwork,
      context: Context,
      goal: LocalState,
      maxPaths: Int = 1024
  ): LocalCausalityGraph =
    val solutions = scala.collection.mutable.Map.empty[LocalState, List[Solution]]

    def expand(target: LocalState): Unit =
      if solutions.contains(target) then ()
      else
        solutions(target) = Nil // marque en cours (évite la récursion infinie)
        net.automaton(target.automaton) match
          case None =>
            solutions(target) = Nil
          case Some(au) =>
            val initLevels = context.levelsOf(au.name, au.states)
            val sols =
              for
                start <- initLevels.toList.sorted
                path <- LocalPath.enumerate(au, start, target.level, maxPaths)
              yield Solution(path)
            solutions(target) = sols
            for s <- sols; cond <- s.requires do expand(cond)

    expand(goal)
    val fromLevels = context.levelsOf(goal.automaton, net.levelsOf(goal.automaton))
    LocalCausalityGraph(net, context, Objective(goal, fromLevels), solutions.toMap)
