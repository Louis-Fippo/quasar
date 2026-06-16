package io.quasar.analysis

import io.quasar.core.dd.{Mdd, MtOp}
import io.quasar.core.ir.*

/**
 * Backend symbolique **multivalué** (§6.4) par MDD : atteignabilité, points fixes et `P(R)` exacts
 * pour les réseaux à `|S(a)| ≥ 2` (généralise le BDD/MTBDD booléen). Encodage entrelacé courant
 * `2a` / suivant `2a+1`, chaque variable de domaine `|S(a)|`. Relation de transition asynchrone,
 * ensemble atteignable par plus petit point fixe d'image, `P(R)` par itération de valeur — sans
 * énumérer l'espace d'états.
 */
object SymbolicMdd:

  final case class ReachResult(goalReachable: Boolean, reachableStates: Long, mddNodes: Int)
  final case class ProbResult(reachProbability: Double, iterations: Int, mddNodes: Int)

  /** Atteignabilité exacte (réseaux booléens ou multivalués). */
  def reachability(net: AutomataNetwork, ctx: Context, goal: LocalState): ReachResult =
    val e = Encoding(net)
    val reach = e.reachable(ctx)
    val goalSet = e.predCur(goal.automaton, goal.level)
    val count = e.m.eval(e.m.abstractSum(reach, e.curVars), _ => 0) // somme totale -> constante
    ReachResult(e.m.isSat(e.m.and(reach, goalSet)), count.toLong, e.m.nodeCount(reach))

  /** Nombre de points fixes (états sans transition tirable). */
  def fixpointCount(net: AutomataNetwork): Long =
    val e = Encoding(net)
    val hasSucc = e.m.abstractMax(e.transition01, e.nextVars)
    val deadlock = e.m.not(hasSucc)
    e.m.eval(e.m.abstractSum(deadlock, e.curVars), _ => 0).toLong

  /** `P(R)` exacte par itération de valeur (transitions exponentielles requises). */
  def reachProbability(
      net: AutomataNetwork,
      ctx: Context,
      goal: LocalState,
      maxIters: Int = 100_000,
      tol: Double = 1e-12
  ): Either[String, ProbResult] =
    if net.transitions.exists(!_.dist.isExponential) then
      Left("P(R) symbolique : transitions exponentielles requises (phase-type non supporté)")
    else Right(Encoding(net).probability(ctx, goal, maxIters, tol))

  /** Encodage MDD d'un réseau (courant/suivant, domaines = niveaux). */
  private final class Encoding(net: AutomataNetwork):
    val m = Mdd()
    private val order = net.ordered.map(_.name).zipWithIndex.toMap
    private def cur(a: String): Int = 2 * order(a)
    private def nxt(a: String): Int = 2 * order(a) + 1
    val curVars: List[Int] = net.ordered.toList.map(au => cur(au.name))
    val nextVars: List[Int] = net.ordered.toList.map(au => nxt(au.name))

    // déclaration des domaines (mêmes niveaux pour la couche courante et suivante)
    for au <- net.ordered do
      m.setDomain(cur(au.name), au.levels)
      m.setDomain(nxt(au.name), au.levels)

    def predCur(a: String, l: Int): Int = m.predicate(cur(a), l)
    private def predNxt(a: String, l: Int): Int = m.predicate(nxt(a), l)
    private def eqCurNxt(a: String): Int =
      m.orAll((0 until net.automaton(a).get.levels).map(l => m.and(predCur(a, l), predNxt(a, l))))

    /** Relation `relationₜ(x, x')` (indicateur 0/1) d'une transition. */
    private def relation01(t: Transition): Int =
      val guard = m.andAll(
        predCur(t.automaton, t.from) :: t.conditions.map(c => predCur(c.automaton, c.level))
      )
      val effect = predNxt(t.automaton, t.to)
      val frame =
        m.andAll(net.ordered.iterator.filter(_.name != t.automaton).map(b => eqCurNxt(b.name)))
      m.and(m.and(guard, effect), frame)

    /** Relation de transition globale (0/1). */
    val transition01: Int = m.orAll(net.transitions.map(relation01))

    private def initial(ctx: Context): Int =
      m.andAll(net.ordered.map { au =>
        val levels = ctx.levelsOf(au.name, au.states)
        if levels.size == 1 then predCur(au.name, levels.head)
        else m.orAll(levels.toList.map(l => predCur(au.name, l)))
      })

    private def image(s: Int): Int =
      val ex = m.abstractMax(m.and(s, transition01), curVars)
      m.relabel(ex, v => if v % 2 == 1 then v - 1 else v) // x' -> x

    def reachable(ctx: Context): Int =
      var r = initial(ctx)
      var changed = true
      while changed do
        val nx = m.or(r, image(r))
        changed = nx != r
        r = nx
      r

    def probability(ctx: Context, goal: LocalState, maxIters: Int, tol: Double): ProbResult =
      // matrice de taux pondérée
      val tRate = net.transitions.foldLeft(m.zero) { (acc, t) =>
        m.add(acc, m.mul(m.constant(t.rate), relation01(t)))
      }
      val goalInd = predCur(goal.automaton, goal.level)
      val notGoal = m.not(goalInd)
      val tAbs = m.mul(tRate, notGoal)
      val exitR = m.abstractSum(tAbs, nextVars)
      val pMat = m.apply(MtOp.Div, tAbs, exitR)

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

      val starts = net.ordered.foldLeft(List(Map.empty[Int, Int])) { (acc, au) =>
        val levels = ctx.levelsOf(au.name, au.states).toList.sorted
        for s <- acc; l <- levels yield s.updated(cur(au.name), l)
      }
      val prob =
        if starts.isEmpty then 0.0
        else starts.map(s => m.eval(h, v => s.getOrElse(v, 0))).sum / starts.size
      ProbResult(prob, iters, m.nodeCount(h))
