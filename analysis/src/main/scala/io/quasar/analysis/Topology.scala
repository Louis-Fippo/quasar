package io.quasar.analysis

import io.quasar.core.glc.{InteractionGraph, Sign}
import io.quasar.core.ir.*
import io.quasar.core.solver.Scc

/** Circuit (cycle élémentaire) signé du graphe d'interaction. */
final case class Circuit(nodes: List[String], sign: Sign):
  override def toString: String =
    val s = sign match
      case Sign.Positive => "+"
      case Sign.Negative => "-"
      case Sign.Dual => "±"
    s"[$s] ${(nodes :+ nodes.head).mkString(" -> ")}"

/** Résultat d'une analyse bornée (peut être tronquée). */
final case class Bounded[A](items: List[A], truncated: Boolean)

/**
 * Analyses topologiques structurelles et d'attracteurs (§7.3).
 *
 * Les analyses structurelles (SCC, circuits, feedback) opèrent sur le graphe d'interaction et sont
 * polynomiales. Les analyses dynamiques (fixpoints, attracteurs, trap-spaces) explorent l'espace
 * d'états et sont **bornées** par un plafond ; au-delà, le résultat est marqué tronqué (justesse
 * §10).
 */
object Topology:

  type Global = Map[String, Int]

  // --- structurel ----------------------------------------------------------

  /** SCC non triviales du graphe d'interaction (composants en rétroaction). */
  def scc(net: AutomataNetwork): List[Set[String]] =
    val ig = InteractionGraph.of(net)
    Scc.nonTrivial(net.automata.keys, n => ig.successors(n))

  /** Circuits élémentaires signés ; `filter` restreint au signe voulu. */
  def circuits(net: AutomataNetwork, filter: Option[Sign] = None): List[Circuit] =
    val ig = InteractionGraph.of(net)
    val nodes = net.automata.keys.toList.sorted
    val order = nodes.zipWithIndex.toMap
    val found = scala.collection.mutable.ListBuffer.empty[Circuit]

    // énumère les cycles élémentaires en ne démarrant qu'au plus petit nœud
    def dfs(start: String, current: String, path: List[String], onPath: Set[String]): Unit =
      for inf <- ig.nodesOut(current) do
        val nxt = inf.to
        if nxt == start && path.nonEmpty then
          val sign = path.sliding(2).foldLeft(Sign.Positive) {
            case (acc, List(a, b)) => acc * ig.signOf(a, b).getOrElse(Sign.Dual)
            case (acc, _) => acc
          } * ig.signOf(current, start).getOrElse(Sign.Dual)
          found += Circuit(path, sign)
        else if order(nxt) > order(start) && !onPath.contains(nxt) then
          dfs(start, nxt, path :+ nxt, onPath + nxt)

    for s <- nodes do dfs(s, s, List(s), Set(s))
    val all = found.toList.distinctBy(c => c.nodes.toSet -> c.sign)
    filter match
      case None => all
      case Some(f) => all.filter(_.sign == f)

  // --- dynamique (bornée) --------------------------------------------------

  private def firable(s: Global, t: Transition): Boolean =
    s.get(t.automaton).contains(t.from) &&
      t.conditions.forall(c => s.get(c.automaton).contains(c.level))

  /** Énumère l'espace d'états global (produit), borné par `cap`. */
  private def allStates(net: AutomataNetwork, cap: Int): (List[Global], Boolean) =
    val autos = net.ordered
    val total = autos.map(_.levels.toLong).product
    if total > cap then (Nil, true)
    else
      val states = autos.foldLeft(List(Map.empty[String, Int])) { (acc, au) =>
        for s <- acc; l <- au.states.toList yield s.updated(au.name, l)
      }
      (states, false)

  /** Points fixes : états globaux sans aucune transition tirable. */
  def fixpoints(net: AutomataNetwork, cap: Int = 1_000_000): Bounded[Global] =
    val (states, truncated) = allStates(net, cap)
    val fps = states.filter(s => !net.transitions.exists(firable(s, _)))
    Bounded(fps, truncated)

  /**
   * Attracteurs (méthode exacte, bornée) : SCC terminales du graphe de transition d'états
   * (asynchrone). Une SCC terminale de taille 1 est un point fixe ; de taille > 1, un attracteur
   * cyclique.
   */
  def attractors(net: AutomataNetwork, cap: Int = 200_000): Bounded[List[Global]] =
    val (states, truncated) = allStates(net, cap)
    if truncated then Bounded(Nil, true)
    else
      def succ(s: Global): List[Global] =
        net.transitions.filter(firable(s, _)).map(t => s.updated(t.automaton, t.to)).distinct.toList
      val comps = Scc.compute(states, succ)
      val compOf = comps.zipWithIndex.flatMap { case (c, i) => c.map(_ -> i) }.toMap
      val terminal = comps.filter { c =>
        c.forall(s => succ(s).forall(ns => compOf(ns) == compOf(c.head)))
      }
      Bounded(terminal.map(_.toList), false)

  /**
   * Trap-spaces (sous-hypercubes clos) — énumération bornée.
   *
   * Un sous-espace `P` (certains automates figés à un niveau, les autres libres) est un trap-space
   * ssi, pour tout automate figé à `v`, aucune transition `v -> w` n'est satisfiable dans `P` (une
   * précondition fixée la contredit). `minimalOnly` ne conserve que les trap-spaces minimaux pour
   * l'inclusion.
   */
  def trapSpaces(
      net: AutomataNetwork,
      minimalOnly: Boolean = false,
      cap: Int = 1_000_000
  ): Bounded[Map[String, Int]] =
    val autos = net.ordered
    // chaque automate : libre (None) ou figé à un niveau ; combos = Π (levels+1)
    val total = autos.map(_.levels.toLong + 1).product
    if total > cap then Bounded(Nil, true)
    else
      val partials = autos.foldLeft(List(Map.empty[String, Int])) { (acc, au) =>
        val choices: List[Option[Int]] = None :: au.states.toList.map(Some(_))
        for s <- acc; c <- choices yield c.fold(s)(l => s.updated(au.name, l))
      }
      def isTrap(p: Map[String, Int]): Boolean =
        net.transitions.forall { t =>
          p.get(t.automaton) match
            case Some(v) if v == t.from =>
              // transition sortante d'un automate figé : doit être insatisfiable dans P
              t.conditions.exists(c => p.get(c.automaton).exists(_ != c.level))
            case _ => true
        }
      val traps = partials.filter(isTrap)
      val result =
        if !minimalOnly then traps
        else
          // p minimal ssi aucun trap q strictement à l'intérieur (q ⊆ p) :
          // q ⊆ p ⟺ ∀(k,v)∈p, q fige k à v (q au moins aussi contraint).
          traps.filter(p =>
            !traps.exists(q => q != p && p.forall { case (k, v) => q.get(k).contains(v) })
          )
      Bounded(result, false)
