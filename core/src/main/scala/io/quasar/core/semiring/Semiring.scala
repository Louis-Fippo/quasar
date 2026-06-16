package io.quasar.core.semiring

/**
 * Semi-anneau `(T, ⊕, ⊗, zero, one)`.
 *
 * Toute analyse de chemin de QUASAR est paramétrée par un `Semiring[T]` (D2, conventions §9) : on
 * ne code jamais en dur « proba » ou « délai » dans les algorithmes de parcours.
 *
 * Lois (vérifiées par property tests) :
 *   - `⊕` associatif, commutatif, neutre `zero`
 *   - `⊗` associatif, neutre `one`
 *   - `⊗` distribue sur `⊕`
 *   - `zero` absorbant pour `⊗`
 */
trait Semiring[T]:
  def zero: T
  def one: T
  def plus(a: T, b: T): T
  def times(a: T, b: T): T

  extension (a: T)
    inline def ⊕(b: T): T = plus(a, b)
    inline def ⊗(b: T): T = times(a, b)

object Semiring:
  def apply[T](using s: Semiring[T]): Semiring[T] = s
