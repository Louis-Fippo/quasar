package io.quasar.core.glc

import io.quasar.core.ir.*

/**
 * Chemin local acyclique dans un seul automate : suite ordonnée de transitions de `from` à `to`.
 * C'est l'unité de base d'une « solution » du GLC.
 */
final case class LocalPath(automaton: String, from: Int, to: Int, steps: List[Transition]):

  /** Préconditions cumulées (dédupliquées) le long du chemin = sous-objectifs. */
  def conditions: List[LocalState] = steps.flatMap(_.conditions).distinct

  /** Longueur (nombre de transitions). */
  def length: Int = steps.size

  override def toString: String =
    if steps.isEmpty then s"$automaton=$from (déjà atteint)"
    else s"$automaton: ${(from :: steps.map(_.to)).mkString(" -> ")}"

object LocalPath:

  /**
   * Énumère les chemins locaux acycliques (simples) de `from` à `to` dans `au`.
   *
   * ⚠ Source d'explosion (cf. CLAUDE.md §10) : à n'utiliser que pour les petits automates ou bornée
   * par `maxPaths`. Les solveurs quantitatifs préfèrent le chemin algébrique. `maxLen` borne la
   * longueur explorée.
   */
  def enumerate(
      au: Automaton,
      from: Int,
      to: Int,
      maxPaths: Int = 1024,
      maxLen: Int = 64
  ): List[LocalPath] =
    if from == to then List(LocalPath(au.name, from, to, Nil))
    else
      val acc = List.newBuilder[LocalPath]
      var count = 0

      def dfs(current: Int, visited: Set[Int], steps: List[Transition]): Unit =
        if count >= maxPaths || steps.length >= maxLen then ()
        else
          for t <- au.outgoing(current) if !visited.contains(t.to) && count < maxPaths do
            val nextSteps = steps :+ t
            if t.to == to then
              acc += LocalPath(au.name, from, to, nextSteps)
              count += 1
            else dfs(t.to, visited + t.to, nextSteps)

      dfs(from, Set(from), Nil)
      acc.result()
