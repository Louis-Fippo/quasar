package io.quasar.core.semiring

import org.scalacheck.Prop.forAll
import org.scalacheck.Gen

/** Vérifie les lois de semi-anneau par property-based testing (CLAUDE.md §9). */
class SemiringLawsSuite extends munit.ScalaCheckSuite:

  import Instances.given

  // Générateurs bornés (évite NaN/∞ pathologiques)
  private val finite: Gen[Double] = Gen.choose(0.0, 1e6)
  private val prob: Gen[Double] = Gen.choose(0.0, 1.0)

  private def laws[T](name: String, g: Gen[T], approxEq: (T, T) => Boolean)(using
      S: Semiring[T]
  ): Unit =
    property(s"$name: ⊕ commutatif") {
      forAll(g, g)((a, b) => approxEq(S.plus(a, b), S.plus(b, a)))
    }
    property(s"$name: ⊕ associatif") {
      forAll(g, g, g)((a, b, c) => approxEq(S.plus(S.plus(a, b), c), S.plus(a, S.plus(b, c))))
    }
    property(s"$name: neutre ⊕ = zero") {
      forAll(g)(a => approxEq(S.plus(a, S.zero), a))
    }
    property(s"$name: neutre ⊗ = one") {
      forAll(g)(a => approxEq(S.times(a, S.one), a))
    }
    property(s"$name: ⊗ associatif") {
      forAll(g, g, g)((a, b, c) => approxEq(S.times(S.times(a, b), c), S.times(a, S.times(b, c))))
    }
    property(s"$name: zero absorbant pour ⊗") {
      forAll(g)(a => approxEq(S.times(a, S.zero), S.zero))
    }
    property(s"$name: ⊗ distribue sur ⊕") {
      forAll(g, g, g)((a, b, c) =>
        approxEq(S.times(a, S.plus(b, c)), S.plus(S.times(a, b), S.times(a, c)))
      )
    }

  private def eqD(tol: Double)(a: Double, b: Double): Boolean =
    if a.isInfinite || b.isInfinite then a == b
    else math.abs(a - b) <= tol * (1.0 + math.abs(a) + math.abs(b))

  laws("Tropical (min,+)", finite, eqD(1e-9))(using Instances.Tropical)

  // Viterbi & ProbAgg manipulent des opaque types -> on teste via les valeurs.
  laws[Instances.Prob](
    "Viterbi (max,×)",
    prob.map(Instances.Prob.apply),
    (a, b) => eqD(1e-9)(Instances.Prob.value(a), Instances.Prob.value(b))
  )(using Instances.Viterbi)

  laws[Instances.ProbSum](
    "ProbAgg (+,×)",
    prob.map(Instances.ProbSum.apply),
    (a, b) => eqD(1e-9)(Instances.ProbSum.value(a), Instances.ProbSum.value(b))
  )(using Instances.ProbAgg)

  laws[Boolean]("Bool (∨,∧)", Gen.oneOf(true, false), _ == _)(using Instances.BoolReach)
