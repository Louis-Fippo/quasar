package io.quasar.analysis

import io.quasar.core.glc.LocalCausalityGraph
import io.quasar.core.ir.*
import io.quasar.core.solver.CtmcSolver
import io.quasar.core.{Approx, Bound}

/**
 * Résultat quantitatif (chap. 4) : probabilité de `R` (exacte si la CTMC du cône est résolue, borne
 * inférieure sinon), délai au plus tôt `T(R)`, temps moyen d'absorption `meanTime` (si le but est
 * atteint p.s.), et scénario témoin.
 */
final case class QuantResult(
    goal: LocalState,
    probLowerBound: Option[Bound[Double]],
    earliestDelay: Option[Bound[Double]],
    meanTime: Option[Bound[Double]],
    scenario: List[Transition]
)

/**
 * Analyse quantitative statique du `⌈Gω_ς⌉` (chap. 4 de la thèse).
 *
 *   - **`P(R)`** : résolution **exacte** par CTMC absorbante locale sur le cône d'influence
 *     (matrice fondamentale, §6.5) — les cycles sont traités sans énumérer les chemins. Si l'espace
 *     d'états du cône dépasse le plafond, on retombe sur une borne inférieure sound : probabilité
 *     d'un scénario témoin, chaque branchement minoré par `taux(t)/Λ` (`Λ = Σ taux`). Dans tous les
 *     cas `binf P(R) ≤ P_exact` (justesse §10).
 *   - **`T(R)` (délai au plus tôt)** : plus petit délai espéré sur l'arbre causal, accumulé
 *     séquentiellement le long du chemin (préconditions parallèles) ; `min` sur les solutions.
 *     Minoration du temps réel.
 *   - **`meanTime`** : temps moyen d'absorption (CTMC), défini si `P(R) ≈ 1`.
 */
object Quantitative:

  def analyze(
      net: AutomataNetwork,
      ctx: Context,
      goal: LocalState,
      maxStates: Int = 100_000,
      anytimeK: Int = 64
  ): QuantResult =
    val reach = Reachability.analyze(net, ctx, goal)
    val scenario = reach.witness.map(_.transitions).getOrElse(Nil)
    // expansion phase-type (D4) : les calculs quantitatifs opèrent sur le réseau
    // expansé (compétition exacte) ; sans dist non triviale, `exp eq net`.
    val exp = Transform.expandPhaseType(net)
    val ctmc = CtmcSolver.solve(exp, ctx, goal, maxStates)
    val prob = ctmc match
      case Some(r) => Some(Bound(r.reachProbability, Approx.Exact))
      case None =>
        // cône trop grand pour la CTMC exacte : borne anytime (trajectoires
        // disjointes) si atteignable, sinon repli sur la borne témoin/Λ.
        if reach.oaReachable then
          Some(Bound(Scenarios.anytimeLowerBound(exp, ctx, goal, anytimeK), Approx.Under))
        else probabilityLowerBound(exp, reach)
    val meanTime = ctmc.flatMap(_.expectedTime).map(Bound(_, Approx.Exact))
    val delay = earliestDelay(exp, ctx, goal)
    QuantResult(goal, prob, delay, meanTime, scenario)

  // --- P(R) : borne inférieure sound (repli si CTMC non résolue) -----------

  def probabilityLowerBound(
      net: AutomataNetwork,
      reach: ReachabilityResult
  ): Option[Bound[Double]] =
    reach.witness.map { w =>
      val totalRate = net.transitions.iterator.map(_.rate).sum
      val p =
        if totalRate <= 0 then 0.0
        else w.transitions.foldLeft(1.0)((acc, t) => acc * (t.rate / totalRate))
      Bound(p, Approx.Under)
    }

  // --- T(R) : délai au plus tôt --------------------------------------------

  def earliestDelay(net: AutomataNetwork, ctx: Context, goal: LocalState): Option[Bound[Double]] =
    val glc = LocalCausalityGraph.build(net, ctx, goal)

    def initialLevel(a: String): Option[Int] =
      net.automaton(a).flatMap { au =>
        val ls = ctx.levelsOf(a, au.states)
        if ls.size == 1 then Some(ls.head) else None
      }

    val memo = scala.collection.mutable.Map.empty[LocalState, Double]

    def delayOf(ls: LocalState, inProgress: Set[LocalState]): Double =
      if initialLevel(ls.automaton).contains(ls.level) then 0.0
      else if inProgress.contains(ls) then Double.PositiveInfinity
      else
        memo.getOrElse(
          ls, {
            val sols = glc.solutions.getOrElse(ls, Nil)
            val v =
              if sols.isEmpty then Double.PositiveInfinity
              else
                // pour chaque solution : accumulation séquentielle le long du chemin ;
                // une étape ne peut se tirer qu'une fois ses préconditions établies
                // (parallèles entre elles), d'où max(temps courant, max délais cond.) + délai propre.
                sols.iterator.map { s =>
                  s.path.steps.foldLeft(0.0) { (time, step) =>
                    val condTime =
                      if step.conditions.isEmpty then 0.0
                      else step.conditions.iterator.map(c => delayOf(c, inProgress + ls)).max
                    math.max(time, condTime) + step.dist.mean
                  }
                }.min
            memo(ls) = v
            v
          }
        )

    val d = delayOf(goal, Set.empty)
    if d.isInfinite then None else Some(Bound(d, Approx.Under))
