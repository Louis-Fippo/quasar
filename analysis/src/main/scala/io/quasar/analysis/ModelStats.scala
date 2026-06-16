package io.quasar.analysis

import io.quasar.core.glc.InteractionGraph
import io.quasar.core.ir.AutomataNetwork

/** Métriques structurelles d'un réseau (§7.1 `stats`). */
final case class Stats(
    automata: Int,
    localStates: Int,
    transitions: Int,
    edges: Int,
    density: Double,
    maxInDegree: Int,
    maxOutDegree: Int,
    feedbackComponents: Int
)

/** Différences structurelles entre deux réseaux (§7.1 `diff`). */
final case class Diff(
    onlyInLeft: Set[String],
    onlyInRight: Set[String],
    levelChanges: List[(String, Int, Int)],
    transitionsAdded: List[String],
    transitionsRemoved: List[String]
):
  def isEmpty: Boolean =
    onlyInLeft.isEmpty && onlyInRight.isEmpty && levelChanges.isEmpty &&
      transitionsAdded.isEmpty && transitionsRemoved.isEmpty

object ModelStats:

  def stats(net: AutomataNetwork): Stats =
    val ig = InteractionGraph.of(net)
    val n = net.size
    val nodes = net.automata.keys.toList
    val inDeg = nodes.map(a => ig.influences.count(_.to == a))
    val outDeg = nodes.map(a => ig.influences.count(_.from == a))
    val maxEdges = if n > 1 then n.toLong * (n - 1) else 1L
    Stats(
      automata = n,
      localStates = net.localStateCount,
      transitions = net.transitions.size,
      edges = ig.influences.size,
      density = ig.influences.size.toDouble / maxEdges,
      maxInDegree = if inDeg.isEmpty then 0 else inDeg.max,
      maxOutDegree = if outDeg.isEmpty then 0 else outDeg.max,
      feedbackComponents = Topology.scc(net).size
    )

  def diff(left: AutomataNetwork, right: AutomataNetwork): Diff =
    val lk = left.automata.keySet
    val rk = right.automata.keySet
    val levelChanges =
      (lk intersect rk).toList.sorted.flatMap { a =>
        val ll = left.automata(a).levels
        val rl = right.automata(a).levels
        if ll != rl then Some((a, ll, rl)) else None
      }
    val lt = left.transitions.map(_.toString).toSet
    val rt = right.transitions.map(_.toString).toSet
    Diff(
      onlyInLeft = lk diff rk,
      onlyInRight = rk diff lk,
      levelChanges = levelChanges,
      transitionsAdded = (rt diff lt).toList.sorted,
      transitionsRemoved = (lt diff rt).toList.sorted
    )
