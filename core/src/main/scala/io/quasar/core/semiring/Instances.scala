package io.quasar.core.semiring

/**
 * Instances de semi-anneaux utilisées par les solveurs.
 *
 *   - [[Tropical]] : `(min, +)` — délai au plus tôt `T(R)` (chemins additifs).
 *   - [[Viterbi]] : `(max, ×)` — probabilité maximale d'un chemin.
 *   - [[ProbAgg]] : `(+, ×)` — agrégation de probabilités (bornes `P(R)`).
 *   - [[BoolReach]] : `(∨, ∧)` — atteignabilité qualitative.
 */
object Instances:

  /** Semi-anneau tropical `(min, +)` sur `Double` ; `zero = +∞`, `one = 0`. */
  given Tropical: Semiring[Double] with
    val zero: Double = Double.PositiveInfinity
    val one: Double = 0.0
    def plus(a: Double, b: Double): Double = math.min(a, b)
    def times(a: Double, b: Double): Double =
      if a == zero || b == zero then zero else a + b

  /** Wrapper pour le semi-anneau de Viterbi `(max, ×)` sur `[0,1]`. */
  opaque type Prob = Double
  object Prob:
    def apply(d: Double): Prob = d
    extension (p: Prob) def value: Double = p

  /** Viterbi `(max, ×)` : probabilité du meilleur chemin ; `zero = 0`, `one = 1`. */
  given Viterbi: Semiring[Prob] with
    val zero: Prob = Prob(0.0)
    val one: Prob = Prob(1.0)
    def plus(a: Prob, b: Prob): Prob = Prob(math.max(a, b))
    def times(a: Prob, b: Prob): Prob = Prob(a * b)

  /** Wrapper pour l'agrégation de probabilités `(+, ×)`. */
  opaque type ProbSum = Double
  object ProbSum:
    def apply(d: Double): ProbSum = d
    extension (p: ProbSum) def value: Double = p

  /** Agrégation probabiliste `(+, ×)` ; `zero = 0`, `one = 1`. */
  given ProbAgg: Semiring[ProbSum] with
    val zero: ProbSum = ProbSum(0.0)
    val one: ProbSum = ProbSum(1.0)
    def plus(a: ProbSum, b: ProbSum): ProbSum = ProbSum(a + b)
    def times(a: ProbSum, b: ProbSum): ProbSum = ProbSum(a * b)

  /** Semi-anneau booléen `(∨, ∧)` : atteignabilité qualitative. */
  given BoolReach: Semiring[Boolean] with
    val zero: Boolean = false
    val one: Boolean = true
    def plus(a: Boolean, b: Boolean): Boolean = a || b
    def times(a: Boolean, b: Boolean): Boolean = a && b
