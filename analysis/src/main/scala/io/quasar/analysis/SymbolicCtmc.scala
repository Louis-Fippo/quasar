package io.quasar.analysis

import io.quasar.core.dd.Mtbdd
import io.quasar.core.ir.*

/**
 * Calcul **symbolique** de `P(R)` par MTBDD (§6.4) — itération de valeur sur diagrammes à terminaux
 * réels, sans énumérer l'espace d'états (à la Storm/PRISM).
 *
 * Encodage entrelacé courant `2a` / suivant `2a+1`. On construit la matrice de taux `T(x,x')`
 * (somme pondérée des relations de transition), le taux de sortie `R(x) = ∑_{x'} T`, la matrice de
 * saut `P = T/R`, puis on itère `h_{k+1}(x) = but(x) + (1−but(x))·∑_{x'} P(x,x')·h_k(x')` jusqu'au
 * point fixe. `P(R)` est `h` évalué à l'état initial.
 *
 * Réservé aux réseaux **booléens à transitions exponentielles** (les phase-type, multivaluées après
 * expansion, relèvent du solveur explicite).
 */
object SymbolicCtmc:

  final case class Result(reachProbability: Double, iterations: Int, mtbddNodes: Int)

  def reachProbability(
      net: AutomataNetwork,
      ctx: Context,
      goal: LocalState,
      maxIters: Int = 100_000,
      tol: Double = 1e-12
  ): Either[String, Result] =
    val nonBool = net.automata.values.filter(_.levels != 2).map(_.name).toList
    val nonExp = net.transitions.filter(!_.dist.isExponential)
    if nonBool.nonEmpty then
      Left(s"P(R) symbolique : réseau booléen requis (multivalués : ${nonBool.mkString(", ")})")
    else if nonExp.nonEmpty then
      Left("P(R) symbolique : transitions exponentielles requises (phase-type non supporté)")
    else Right(solve(net, ctx, goal, maxIters, tol))

  private def solve(
      net: AutomataNetwork,
      ctx: Context,
      goal: LocalState,
      maxIters: Int,
      tol: Double
  ): Result =
    val m = Mtbdd()
    val order = net.ordered.map(_.name).zipWithIndex.toMap
    def cur(a: String): Int = 2 * order(a)
    def nxt(a: String): Int = 2 * order(a) + 1
    val nextVars = order.values.toList.map(a => 2 * a + 1)

    def predCur(a: String, lvl: Int): Int = m.predicate(cur(a), lvl)
    def predNxt(a: String, lvl: Int): Int = m.predicate(nxt(a), lvl)
    def eqCurNxt(a: String): Int =
      m.add(m.mul(predCur(a, 0), predNxt(a, 0)), m.mul(predCur(a, 1), predNxt(a, 1)))

    // matrice de taux T(x, x') = ∑_t taux_t · garde · effet · frame
    val tRate = net.transitions.foldLeft(m.zero) { (acc, t) =>
      val guard =
        (predCur(t.automaton, t.from) :: t.conditions.map(c => predCur(c.automaton, c.level)))
          .reduce(m.mul)
      val effect = predNxt(t.automaton, t.to)
      val frame = net.ordered.iterator
        .filter(_.name != t.automaton)
        .map(b => eqCurNxt(b.name))
        .foldLeft(m.one)(m.mul)
      val rel = m.mul(m.mul(guard, effect), frame)
      m.add(acc, m.mul(m.constant(t.rate), rel))
    }

    val goalInd = predCur(goal.automaton, goal.level) // 1.0 sur les états but (couche courante)
    val notGoal = m.sub(m.one, goalInd)
    val tAbs = m.mul(tRate, notGoal) // le but est absorbant
    val exitR = m.abstractSum(tAbs, nextVars) // R(x) sur la couche courante
    val pMat = m.apply(io.quasar.core.dd.MtOp.Div, tAbs, exitR)

    // itération de valeur : h = but + (1-but)·(P·h)
    var h = goalInd
    var iters = 0
    var delta = Double.MaxValue
    while iters < maxIters && delta > tol do
      val hNext = m.relabel(h, v => if v % 2 == 0 then v + 1 else v) // x -> x'
      val mv = m.abstractSum(m.mul(pMat, hNext), nextVars)
      val hNew = m.add(goalInd, m.mul(notGoal, mv))
      delta = m.maxAbsLeaf(m.sub(hNew, h))
      h = hNew
      iters += 1

    // P(R) = moyenne de h sur les états initiaux (produit du contexte)
    val starts = net.ordered.foldLeft(List(Map.empty[Int, Int])) { (acc, au) =>
      val levels = ctx.levelsOf(au.name, au.states).toList.sorted
      for s <- acc; l <- levels yield s.updated(cur(au.name), l)
    }
    val prob =
      if starts.isEmpty then 0.0
      else starts.map(s => m.eval(h, v => s.getOrElse(v, 0))).sum / starts.size
    Result(prob, iters, m.nodeCount(h))
