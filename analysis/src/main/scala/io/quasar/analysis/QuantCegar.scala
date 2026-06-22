package io.quasar.analysis

import io.quasar.core.glc.Cone
import io.quasar.core.ir.*

/**
 * Encadrement quantitatif sound de `P(R)`, raffiné par budget (CEGAR quantitatif).
 */
final case class QuantBracket(lower: Double, upper: Double, budget: Int):
  def width: Double = upper - lower
  def exact(tol: Double = 1e-9): Boolean = width <= tol

/**
 * CEGAR quantitatif (§7.4 / §6.7) : encadre `P(R)` par un intervalle `[lo, hi]` **sound**,
 * convergeant vers la valeur exacte avec le budget.
 *
 * Recherche meilleur-d'abord sur la chaîne de saut du cône d'influence. Deux cibles absorbantes :
 * le **but** et les **états morts** (états d'où le but est OA-inatteignable). Les trajectoires de
 * premier passage distinctes étant mutuellement exclusives :
 *
 *   - `lo = ∑` proba(trajectoires → but) `≤ P(R)`
 *   - `hi = 1 − ∑` proba(trajectoires → mort) `≥ P(R)` (car `∑ ≤ P(¬R)`)
 *
 * L'absorption étant presque sûre dans `but ∪ morts`, `[lo, hi]` converge vers `P(R)` quand le
 * budget croît.
 */
object QuantCegar:

  type Global = Map[String, Int]

  def bracket(
      net: AutomataNetwork,
      ctx: Context,
      goal: LocalState,
      budget: Int = 256,
      maxExpansions: Int = 200_000
  ): QuantBracket =
    // expansion phase-type (D4) : la chaîne de saut doit opérer sur le réseau
    // expansé (Erlang/PhaseType -> chaînes exponentielles), sinon l'approximation
    // par taux moyen rend l'encadrement FAUX (non sound) sur ces modèles.
    val expanded = Transform.expandPhaseType(net)
    val coneNames = Cone.of(expanded, goal.automaton)
    val coneNet = restrict(expanded, coneNames)
    val coneTrans = coneNet.transitions

    def goalHit(s: Global): Boolean = s.get(goal.automaton).contains(goal.level)

    def enabled(s: Global): Vector[Transition] =
      coneTrans.filter(t =>
        s.get(t.automaton).contains(t.from) &&
          t.conditions.forall(c => s.get(c.automaton).contains(c.level))
      )

    // mort(s) : le but est OA-inatteignable depuis s (donc jamais atteint)
    val deadMemo = scala.collection.mutable.Map.empty[Global, Boolean]
    def dead(s: Global): Boolean =
      deadMemo.getOrElseUpdate(
        s, {
          val from = Context(s.map((a, l) => a -> Set(l)))
          !Reachability.mayReach(coneNet, from).contains(goal)
        }
      )

    def absorbing(s: Global): Boolean = goalHit(s) || dead(s)

    val starts = coneNet.ordered.foldLeft(List(Map.empty[String, Int])) { (acc, au) =>
      val levels = ctx.levelsOf(au.name, au.states).toList.sorted
      for s <- acc; l <- levels yield s.updated(au.name, l)
    }

    // somme des proba des trajectoires (premier passage) acceptées par `accept`
    def search(start: Global, accept: Global => Boolean): Double =
      given Ordering[(Double, Global)] = Ordering.by(_._1)
      val pq = scala.collection.mutable.PriorityQueue.empty[(Double, Global)]
      pq.enqueue((1.0, start))
      var sum = 0.0
      var taken = 0
      var expansions = 0
      while pq.nonEmpty && taken < budget && expansions < maxExpansions do
        val (p, s) = pq.dequeue()
        expansions += 1
        if absorbing(s) then
          if accept(s) then { sum += p; taken += 1 }
        else
          val en = enabled(s)
          val exit = en.iterator.map(_.rate).sum
          if exit > 0 then
            for t <- en do pq.enqueue((p * (t.rate / exit), s.updated(t.automaton, t.to)))
      math.min(sum, 1.0)

    if starts.isEmpty then QuantBracket(0.0, 1.0, budget)
    else
      val lo = starts.map(s => search(s, goalHit)).sum / starts.size
      val hiComp = starts.map(s => search(s, dead)).sum / starts.size
      QuantBracket(lo, math.max(lo, 1.0 - hiComp), budget)

  private def restrict(net: AutomataNetwork, keep: Set[String]): AutomataNetwork =
    net.copy(automata = net.automata.view.filterKeys(keep).toMap)
