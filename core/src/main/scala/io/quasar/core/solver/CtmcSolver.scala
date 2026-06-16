package io.quasar.core.solver

import io.quasar.core.glc.Cone
import io.quasar.core.ir.*

/**
 * Solveur de CTMC absorbante locale (§6.5) — résolution exacte des cycles via la matrice
 * fondamentale, sans énumérer les chemins.
 *
 * On construit la chaîne sur les états atteignables du **cône d'influence** du but (sous-système
 * autonome). La probabilité d'atteindre éventuellement le but dans la CTMC globale égale celle de
 * la chaîne de saut du cône : les transitions hors-cône sont du « bégaiement » qui ne change pas la
 * probabilité éventuelle, et la compétition entre transitions du cône se normalise sur les seuls
 * taux du cône.
 *
 * Pour un état transitoire `i`, `h_i = P(atteindre le but | départ i)` vérifie `h_i = Σ_j p_ij h_j`
 * avec `h=1` sur le but, `h=0` sur les autres absorbants ; d'où le système `(I − P_TT) h = b`. Le
 * temps moyen d'absorption résout `(I − P_TT) t = τ`, `τ_i = 1/R_i`.
 */
object CtmcSolver:

  /** Probabilité exacte d'atteignabilité et (optionnellement) temps moyen. */
  final case class Result(reachProbability: Double, expectedTime: Option[Double], states: Int)

  type Global = Map[String, Int]

  /**
   * Résout la CTMC du cône. Renvoie `None` si l'espace d'états atteignable du cône dépasse
   * `maxStates` (repli sur les bornes statiques par l'appelant).
   */
  def solve(
      net: AutomataNetwork,
      ctx: Context,
      goal: LocalState,
      maxStates: Int = 100_000
  ): Option[Result] =
    val relevant = Cone.of(net, goal.automaton)
    val coneAutos = net.ordered.filter(au => relevant.contains(au.name))
    val coneTrans = coneAutos.flatMap(_.transitions).toVector

    val starts: List[Global] =
      coneAutos.foldLeft(List(Map.empty[String, Int])) { (acc, au) =>
        val levels = ctx.levelsOf(au.name, au.states).toList.sorted
        for s <- acc; l <- levels yield s.updated(au.name, l)
      }
    if starts.isEmpty then return None

    def hit(s: Global): Boolean = s.get(goal.automaton).contains(goal.level)
    def firable(s: Global, t: Transition): Boolean =
      s.get(t.automaton).contains(t.from) &&
        t.conditions.forall(c => s.get(c.automaton).contains(c.level))

    // --- énumération des états atteignables (le but est absorbant) ----------
    val index = scala.collection.mutable.LinkedHashMap.empty[Global, Int]
    val queue = scala.collection.mutable.Queue.empty[Global]
    def register(s: Global): Unit =
      if !index.contains(s) then { index(s) = index.size; queue.enqueue(s) }
    starts.foreach(register)

    while queue.nonEmpty do
      if index.size > maxStates then return None
      val s = queue.dequeue()
      if !hit(s) then // le but est absorbant : on n'explore pas ses successeurs
        for t <- coneTrans if firable(s, t) do register(s.updated(t.automaton, t.to))
    if index.size > maxStates then return None

    val states = index.toVector.sortBy(_._2).map(_._1)
    val n = states.length

    // taux cumulés des successeurs (somme des taux pour un même état cible)
    def successors(s: Global): Map[Global, Double] =
      if hit(s) then Map.empty
      else
        val acc = scala.collection.mutable.Map.empty[Global, Double]
        for t <- coneTrans if firable(s, t) do
          acc.updateWith(s.updated(t.automaton, t.to))(o => Some(o.getOrElse(0.0) + t.rate))
        acc.toMap

    // partition transitoires / absorbants
    val isAbsorbing = states.map(s => hit(s) || successors(s).isEmpty)
    val transientIdx = (0 until n).filter(i => !isAbsorbing(i)).toVector
    val tPos = transientIdx.zipWithIndex.toMap
    val m = transientIdx.length

    // but déjà atteint à l'initial -> probabilité 1
    if starts.forall(hit) then return Some(Result(1.0, Some(0.0), n))

    // --- système creux (I - P_TT) h = b résolu par itération de Gauss-Seidel.
    // Stockage creux : pour chaque transitoire, ses successeurs transitoires
    // (col, proba) ; `bAbs` = proba d'absorption directe dans le but ; `tau`=1/R_i.
    val rows = new Array[Array[(Int, Double)]](m)
    val bAbs = new Array[Double](m)
    val tau = new Array[Double](m)
    for (gi, ri) <- transientIdx.zipWithIndex do
      val succ = successors(states(gi))
      val rate = succ.values.sum
      tau(ri) = if rate > 0 then 1.0 / rate else 0.0
      val buf = scala.collection.mutable.ArrayBuffer.empty[(Int, Double)]
      for (target, w) <- succ do
        val p = w / rate
        if hit(target) then bAbs(ri) += p
        else tPos.get(index(target)).foreach(col => buf += ((col, p)))
      rows(ri) = buf.toArray

    /** Gauss-Seidel pour `x = b + P x` (substochastique → convergent). */
    def gaussSeidel(b: Array[Double], maxIters: Int = 100_000, tol: Double = 1e-13): Array[Double] =
      val x = new Array[Double](m)
      var iter = 0
      var delta = Double.MaxValue
      while iter < maxIters && delta > tol do
        delta = 0.0
        var i = 0
        while i < m do
          var sum = b(i)
          val row = rows(i)
          var j = 0
          while j < row.length do
            sum += row(j)._2 * x(row(j)._1)
            j += 1
          val d = math.abs(sum - x(i))
          if d > delta then delta = d
          x(i) = sum
          i += 1
        iter += 1
      x

    val h = gaussSeidel(bAbs)
    def probOf(s: Global): Double =
      if hit(s) then 1.0 else tPos.get(index(s)).map(h).getOrElse(0.0)
    val prob = starts.map(probOf).sum / starts.size

    // temps moyen significatif seulement si le but est atteint presque sûrement
    val expected =
      if prob > 1.0 - 1e-9 then
        val t = gaussSeidel(tau)
        def timeOf(s: Global): Double =
          if hit(s) then 0.0 else tPos.get(index(s)).map(t).getOrElse(0.0)
        Some(starts.map(timeOf).sum / starts.size)
      else None
    Some(Result(prob, expected, n))
