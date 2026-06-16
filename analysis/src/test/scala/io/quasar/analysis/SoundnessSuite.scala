package io.quasar.analysis

import io.quasar.core.ir.*
import io.quasar.core.solver.CtmcSolver
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.forAll

/**
 * Justesse des approximations (CLAUDE.md §9, §10) confrontée à l'oracle exact :
 *   - UA atteignable ⇒ exactement atteignable (sous-approximation sound)
 *   - exactement atteignable ⇒ OA atteignable (sur-approximation sound)
 *   - bornes quantitatives dans les plages valides.
 */
class SoundnessSuite extends munit.ScalaCheckSuite:

  // Générateur de petits réseaux booléens aléatoires (2-3 automates, 2 niveaux).
  private val genNet: Gen[AutomataNetwork] =
    for
      k <- Gen.choose(2, 3)
      names = (0 until k).map(i => s"x$i").toList
      autos <- Gen.sequence[List[Automaton], Automaton](names.map(genAutomaton(_, names)))
    yield AutomataNetwork.of(autos*)

  private def genAutomaton(name: String, all: List[String]): Gen[Automaton] =
    for
      nt <- Gen.choose(0, 3)
      trans <- Gen.listOfN(nt, genTransition(name, all))
    yield Automaton(name, 2, trans)

  private def genTransition(name: String, all: List[String]): Gen[Transition] =
    for
      from <- Gen.oneOf(0, 1)
      others = all.filterNot(_ == name)
      nc <- Gen.choose(0, others.size)
      chosen <- Gen.pick(nc, others)
      conds <- Gen.sequence[List[LocalState], LocalState](
        chosen.toList.map(o => Gen.oneOf(0, 1).map(LocalState(o, _)))
      )
      rate <- Gen.choose(0.1, 5.0)
    yield Transition(name, from, 1 - from, conds, Distribution.Exponential(rate))

  // Contexte initial déterministe : tout à 0.
  private def initialAllZero(net: AutomataNetwork): Context =
    Context(net.automata.keys.map(_ -> Set(0)).toMap)

  private val genCase: Gen[(AutomataNetwork, LocalState)] =
    for
      net <- genNet
      a <- Gen.oneOf(net.automata.keys.toList)
      lvl <- Gen.oneOf(0, 1)
    yield (net, LocalState(a, lvl))

  property("UA atteignable ⇒ exactement atteignable (sound)") {
    forAll(genCase) { case (net, goal) =>
      val ctx = initialAllZero(net)
      val r = Reachability.analyze(net, ctx, goal)
      Prop(!r.uaReachable || ExactOracle.reachable(net, ctx, goal))
        .label(s"UA non sound: $goal sur ${net.transitions.mkString("; ")}")
    }
  }

  property("UA = exact sur petits modèles (cône non borné -> complétude)") {
    forAll(genCase) { case (net, goal) =>
      val ctx = initialAllZero(net)
      val r = Reachability.analyze(net, ctx, goal)
      // cône ≤ 3 automates binaires -> BFS exacte, donc UA doit coïncider
      Prop(r.uaReachable == ExactOracle.reachable(net, ctx, goal))
        .label(s"UA≠exact: $goal sur ${net.transitions.mkString("; ")}")
    }
  }

  property("exactement atteignable ⇒ OA atteignable") {
    forAll(genCase) { case (net, goal) =>
      val ctx = initialAllZero(net)
      val r = Reachability.analyze(net, ctx, goal)
      Prop(!ExactOracle.reachable(net, ctx, goal) || r.oaReachable)
        .label(s"OA non sound: $goal sur ${net.transitions.mkString("; ")}")
    }
  }

  property("CTMC : P(R)>0 ⟺ atteignable, et binf statique ≤ P_CTMC (exact)") {
    forAll(genCase) { case (net, goal) =>
      val ctx = initialAllZero(net)
      CtmcSolver.solve(net, ctx, goal) match
        case None => Prop(true) // espace borné : ignoré
        case Some(res) =>
          val r = Reachability.analyze(net, ctx, goal)
          val reachable = res.reachProbability > 1e-12
          val staticLow = Quantitative.probabilityLowerBound(net, r).map(_.value).getOrElse(0.0)
          Prop(
            reachable == r.uaReachable &&
              staticLow <= res.reachProbability + 1e-9 &&
              res.reachProbability <= 1.0 + 1e-9
          ).label(s"CTMC vs statique incohérent: $goal P=${res.reachProbability} binf=$staticLow")
    }
  }

  property("borne inf. P(R) ∈ [0,1] et délai ≥ 0") {
    forAll(genCase) { case (net, goal) =>
      val ctx = initialAllZero(net)
      val q = Quantitative.analyze(net, ctx, goal)
      val pOk = q.probLowerBound.forall(b => b.value >= 0.0 && b.value <= 1.0 + 1e-12)
      val dOk = q.earliestDelay.forall(_.value >= 0.0)
      Prop(pOk && dOk)
    }
  }
