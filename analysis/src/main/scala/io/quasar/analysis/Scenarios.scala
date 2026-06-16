package io.quasar.analysis

import io.quasar.core.glc.Cone
import io.quasar.core.ir.*

/** Un scénario : suite concrète de transitions, sa probabilité et son délai espéré. */
final case class Scenario(transitions: List[Transition], probability: Double, delay: Double)

/**
 * Extraction de scénarios et borne anytime (§7.2 `scenario`, §6.7).
 *
 * Recherche en meilleur-d'abord sur la chaîne de saut du cône d'influence : à un état,
 * `branchProb(t) = taux(t)/Σ taux sortants` et `pas de délai = 1/Σ taux`. On énumère les `k`
 * meilleures trajectoires acycliques vers le but (par probabilité décroissante ou délai croissant).
 *
 * Comme deux trajectoires acycliques distinctes vers le but sont des événements **mutuellement
 * exclusifs**, la somme de leurs probabilités est une **borne inférieure sound de `P(R)`**,
 * monotone croissante en `k` et convergeant vers `P(R)` (anytime, §6.7).
 */
object Scenarios:

  enum Kind:
    case MostProbable, Fastest

  type Global = Map[String, Int]

  private final case class Node(
      state: Global,
      path: List[Transition],
      prob: Double,
      delay: Double
  )

  /** Les `k` meilleurs scénarios vers `goal` (tous départs initiaux confondus). */
  def topK(
      net: AutomataNetwork,
      ctx: Context,
      goal: LocalState,
      k: Int,
      kind: Kind = Kind.MostProbable,
      maxExpansions: Int = 200_000
  ): List[Scenario] =
    starts(net, ctx, goal)
      .flatMap(s => search(net, goal, s, k, kind, maxExpansions))
      .sortBy(sc => if kind == Kind.Fastest then sc.delay else -sc.probability)
      .take(k)

  /**
   * Borne inférieure anytime de `P(R)` : somme des probabilités des `k` meilleures trajectoires
   * (disjointes). Moyenne sur les états initiaux.
   */
  def anytimeLowerBound(
      net: AutomataNetwork,
      ctx: Context,
      goal: LocalState,
      k: Int = 64,
      maxExpansions: Int = 200_000
  ): Double =
    val ss = starts(net, ctx, goal)
    if ss.isEmpty then 0.0
    else
      val perStart = ss.map { s =>
        math.min(
          1.0,
          search(net, goal, s, k, Kind.MostProbable, maxExpansions).map(_.probability).sum
        )
      }
      perStart.sum / perStart.size

  // --- interne -------------------------------------------------------------

  private def starts(net: AutomataNetwork, ctx: Context, goal: LocalState): List[Global] =
    val coneAutos = net.ordered.filter(au => Cone.of(net, goal.automaton).contains(au.name))
    coneAutos.foldLeft(List(Map.empty[String, Int])) { (acc, au) =>
      val levels = ctx.levelsOf(au.name, au.states).toList.sorted
      for s <- acc; l <- levels yield s.updated(au.name, l)
    }

  private def search(
      net: AutomataNetwork,
      goal: LocalState,
      start: Global,
      k: Int,
      kind: Kind,
      maxExpansions: Int
  ): List[Scenario] =
    val relevant = Cone.of(net, goal.automaton)
    val coneTrans =
      net.ordered.filter(au => relevant.contains(au.name)).flatMap(_.transitions).toVector

    def hit(s: Global): Boolean = s.get(goal.automaton).contains(goal.level)
    def enabled(s: Global): Vector[Transition] =
      coneTrans.filter(t =>
        s.get(t.automaton).contains(t.from) &&
          t.conditions.forall(c => s.get(c.automaton).contains(c.level))
      )

    // meilleur-d'abord : max-proba ou min-délai
    given Ordering[Node] =
      if kind == Kind.Fastest then Ordering.by[Node, Double](-_.delay)
      else Ordering.by[Node, Double](_.prob)
    val pq = scala.collection.mutable.PriorityQueue.empty[Node]
    pq.enqueue(Node(start, Nil, 1.0, 0.0))

    // les trajectoires de premier passage (on s'arrête au but) sont mutuellement
    // exclusives, même avec cycles ; on autorise les revisites pour converger
    // (la probabilité décroît strictement, donc le meilleur-d'abord termine).
    val found = scala.collection.mutable.ListBuffer.empty[Scenario]
    var expansions = 0
    while pq.nonEmpty && found.size < k && expansions < maxExpansions do
      val node = pq.dequeue()
      expansions += 1
      if hit(node.state) then found += Scenario(node.path.reverse, node.prob, node.delay)
      else
        val en = enabled(node.state)
        val exit = en.iterator.map(_.rate).sum
        if exit > 0 then
          val stepDelay = 1.0 / exit
          for t <- en do
            val ns = node.state.updated(t.automaton, t.to)
            pq.enqueue(
              Node(ns, t :: node.path, node.prob * (t.rate / exit), node.delay + stepDelay)
            )
    found.toList
