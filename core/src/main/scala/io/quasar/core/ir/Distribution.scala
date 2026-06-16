package io.quasar.core.ir

/**
 * Distribution de délai d'une transition (D4).
 *
 * L'exponentielle est le cas mono-phase et le défaut. Les distributions phase-type sont des
 * distributions de première classe ; elles sont gérées par expansion en phases dans la CTMC (cf.
 * solver). Toute distribution expose son taux effectif `meanRate` (1 / espérance) utilisé comme
 * approximation exponentielle tant que l'expansion phase-type n'est pas requise.
 */
enum Distribution:
  /** Exponentielle de taux `rate` (> 0). Espérance = 1/rate. */
  case Exponential(rate: Double)

  /** Erlang : somme de `phases` exponentielles de même `rate`. Espérance = phases/rate. */
  case Erlang(phases: Int, rate: Double)

  /**
   * Phase-type générale : `phases` phases en série, chacune de taux `rates(i)`. Espérance = somme
   * des 1/rates(i). Forme normale acyclique suffisante pour les délais biologiques usuels.
   */
  case PhaseType(rates: Vector[Double])

  /** Espérance (délai moyen) de la distribution. */
  def mean: Double = this match
    case Exponential(r) => 1.0 / r
    case Erlang(k, r) => k.toDouble / r
    case PhaseType(rs) => rs.map(1.0 / _).sum

  /** Taux effectif = 1 / espérance. Approximation exponentielle mono-phase. */
  def meanRate: Double = 1.0 / mean

  /** Nombre de phases (1 pour l'exponentielle). */
  def phaseCount: Int = this match
    case Exponential(_) => 1
    case Erlang(k, _) => k
    case PhaseType(rs) => rs.size

  /** Vrai si la distribution est mono-phase (exponentielle pure). */
  def isExponential: Boolean = phaseCount == 1

  /**
   * Taux des phases successives (en série). Pour l'exponentielle : `[rate]` ; Erlang(k, r) : `k`
   * fois `r` ; phase-type : les taux donnés. Base de l'expansion en phases dans la CTMC (D4).
   */
  def phaseRates: Vector[Double] = this match
    case Exponential(r) => Vector(r)
    case Erlang(k, r) => Vector.fill(k)(r)
    case PhaseType(rs) => rs

object Distribution:
  /** Distribution par défaut : exponentielle de taux 1. */
  val default: Distribution = Exponential(1.0)
