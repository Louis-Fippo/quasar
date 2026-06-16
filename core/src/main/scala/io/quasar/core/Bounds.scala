package io.quasar.core

/**
 * Direction de garantie formelle d'un résultat (cf. CLAUDE.md §9).
 *
 *   - [[Over]] : sur-approximation — condition *nécessaire* / borne supérieure.
 *   - [[Under]] : sous-approximation — condition *suffisante* / borne inférieure.
 *   - [[Exact]] : valeur exacte.
 */
enum Approx:
  case Over, Under, Exact

/** Verdict d'atteignabilité à trois valeurs. */
enum Verdict:
  case Reachable, Unreachable, Inconclusive

  def label: String = this match
    case Reachable => "ATTEIGNABLE"
    case Unreachable => "INATTEIGNABLE"
    case Inconclusive => "INDÉTERMINÉ"

/**
 * Borne numérique annotée de sa direction de garantie. Une borne inférieure (`Under`) ne doit
 * JAMAIS dépasser la valeur exacte — invariant testé.
 */
final case class Bound[A](value: A, approx: Approx):
  def map[B](f: A => B): Bound[B] = Bound(f(value), approx)

object Bound:
  def lower[A](v: A): Bound[A] = Bound(v, Approx.Under)
  def upper[A](v: A): Bound[A] = Bound(v, Approx.Over)
  def exact[A](v: A): Bound[A] = Bound(v, Approx.Exact)
