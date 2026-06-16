package io.quasar.analysis

import io.quasar.core.ir.*

/**
 * Oracle EXACT (test only) : atteignabilité par BFS sur l'espace d'états global, pour les petits
 * modèles. Sert de référence à la justesse des bornes OA/UA.
 */
object ExactOracle:

  type Global = Map[String, Int]

  /** Tous les états globaux initiaux admis par le contexte (produit cartésien). */
  private def initialStates(net: AutomataNetwork, ctx: Context): List[Global] =
    val perAutomaton = net.ordered.map { au =>
      au.name -> ctx.levelsOf(au.name, au.states).toList.sorted
    }
    perAutomaton.foldLeft(List(Map.empty[String, Int])) { case (acc, (name, levels)) =>
      for s <- acc; l <- levels yield s.updated(name, l)
    }

  private def firable(net: AutomataNetwork, s: Global, t: Transition): Boolean =
    s.get(t.automaton).contains(t.from) &&
      t.conditions.forall(c => s.get(c.automaton).contains(c.level))

  /** Vrai si `goal` est atteignable depuis `ctx` (sémantique asynchrone exacte). */
  def reachable(net: AutomataNetwork, ctx: Context, goal: LocalState): Boolean =
    val seen = scala.collection.mutable.Set.empty[Global]
    val stack = scala.collection.mutable.Stack.empty[Global]
    val starts = initialStates(net, ctx)
    starts.foreach { s =>
      seen += s; stack.push(s)
    }

    def hit(s: Global): Boolean = s.get(goal.automaton).contains(goal.level)

    if starts.exists(hit) then true
    else
      var found = false
      while stack.nonEmpty && !found do
        val s = stack.pop()
        for t <- net.transitions if firable(net, s, t) do
          val ns = s.updated(t.automaton, t.to)
          if hit(ns) then found = true
          else if !seen.contains(ns) then
            seen += ns
            stack.push(ns)
      found

  /** Nombre d'états globaux atteignables depuis `ctx` (BFS exact). */
  def reachableCount(net: AutomataNetwork, ctx: Context): Long =
    val seen = scala.collection.mutable.Set.empty[Global]
    val stack = scala.collection.mutable.Stack.empty[Global]
    initialStates(net, ctx).foreach { s =>
      seen += s; stack.push(s)
    }
    while stack.nonEmpty do
      val s = stack.pop()
      for t <- net.transitions if firable(net, s, t) do
        val ns = s.updated(t.automaton, t.to)
        if !seen.contains(ns) then { seen += ns; stack.push(ns) }
    seen.size.toLong
