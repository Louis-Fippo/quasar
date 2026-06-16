package io.quasar.core.dd

/** Opérations binaires sur les terminaux d'un MTBDD. */
enum MtOp:
  case Add, Mul, Sub, Div, Max

  def apply(a: Double, b: Double): Double = this match
    case Add => a + b
    case Mul => a * b
    case Sub => a - b
    case Div => if b == 0.0 then 0.0 else a / b // 0/0 et x/0 -> 0 (états absorbants)
    case Max => math.max(a, b)

/**
 * Diagramme de décision multi-terminal (MTBDD) auto-contenu — backend symbolique numérique (§6.4).
 * Les variables booléennes sont ordonnées par indice croissant ; les terminaux portent des valeurs
 * `Double`. Sert à représenter la matrice de taux et le vecteur de probabilité pour le calcul
 * symbolique de `P(R)`.
 */
final class Mtbdd:

  private val TERM = Int.MaxValue
  private val vars = scala.collection.mutable.ArrayBuffer.empty[Int]
  private val lows = scala.collection.mutable.ArrayBuffer.empty[Int]
  private val highs = scala.collection.mutable.ArrayBuffer.empty[Int]
  private val leafValue = scala.collection.mutable.ArrayBuffer.empty[Double]
  private val leafIntern = scala.collection.mutable.HashMap.empty[Double, Int]
  private val unique = scala.collection.mutable.HashMap.empty[(Int, Int, Int), Int]
  private val applyCache = scala.collection.mutable.HashMap.empty[(Int, Int, Int), Int]

  private def newNode(v: Int, l: Int, h: Int, value: Double): Int =
    val id = vars.length
    vars += v; lows += l; highs += h; leafValue += value
    id

  /** Terminal de valeur `d`. */
  def constant(d: Double): Int =
    leafIntern.getOrElseUpdate(d, newNode(TERM, -1, -1, d))

  val zero: Int = constant(0.0)
  val one: Int = constant(1.0)

  private def isTerminal(n: Int): Boolean = vars(n) == TERM
  private def varOf(n: Int): Int = vars(n)
  def value(n: Int): Double = leafValue(n)

  /** Crée (ou réutilise) un nœud réduit. */
  def mk(v: Int, l: Int, h: Int): Int =
    require(v < TERM, "mk sur variable terminale")
    if l == h then l
    else unique.getOrElseUpdate((v, l, h), newNode(v, l, h, Double.NaN))

  /** Prédicat 0/1 : `1.0` si la variable `v` vaut `level` (booléen), `0.0` sinon. */
  def predicate(v: Int, level: Int): Int =
    if level == 0 then mk(v, one, zero) else mk(v, zero, one)

  private def cofactor(n: Int, v: Int): (Int, Int) =
    if !isTerminal(n) && vars(n) == v then (lows(n), highs(n)) else (n, n)

  /** Application binaire `op` point à point. */
  def apply(op: MtOp, f: Int, g: Int): Int =
    if isTerminal(f) && isTerminal(g) then constant(op(value(f), value(g)))
    else
      applyCache.getOrElseUpdate(
        (op.ordinal, f, g), {
          val v = math.min(varOf(f), varOf(g))
          val (f0, f1) = cofactor(f, v)
          val (g0, g1) = cofactor(g, v)
          mk(v, apply(op, f0, g0), apply(op, f1, g1))
        }
      )

  def add(f: Int, g: Int): Int = apply(MtOp.Add, f, g)
  def mul(f: Int, g: Int): Int = apply(MtOp.Mul, f, g)
  def sub(f: Int, g: Int): Int = apply(MtOp.Sub, f, g)

  /** Restriction `f|v=b`. */
  def restrict(f: Int, v: Int, b: Boolean): Int =
    if isTerminal(f) || vars(f) > v then f
    else if vars(f) == v then if b then highs(f) else lows(f)
    else mk(vars(f), restrict(lows(f), v, b), restrict(highs(f), v, b))

  /** Somme d'abstraction (∑ sur `v` ∈ {0,1}) : `f|v=0 + f|v=1`. */
  def abstractSum(f: Int, vs: Iterable[Int]): Int =
    vs.foldLeft(f)((acc, v) => add(restrict(acc, v, false), restrict(acc, v, true)))

  /** Renommage de variables (supposé strictement monotone sur le support). */
  def relabel(f: Int, mapVar: Int => Int): Int =
    val memo = scala.collection.mutable.HashMap.empty[Int, Int]
    def go(n: Int): Int =
      if isTerminal(n) then n
      else memo.getOrElseUpdate(n, mk(mapVar(vars(n)), go(lows(n)), go(highs(n))))
    go(f)

  /** Évalue le MTBDD pour une assignation complète des variables pertinentes. */
  def eval(f: Int, assign: Int => Int): Double =
    var n = f
    while !isTerminal(n) do n = if assign(vars(n)) == 1 then highs(n) else lows(n)
    value(n)

  /** Valeur absolue maximale parmi les terminaux accessibles. */
  def maxAbsLeaf(f: Int): Double =
    val seen = scala.collection.mutable.Set.empty[Int]
    var m = 0.0
    def go(n: Int): Unit =
      if seen.add(n) then
        if isTerminal(n) then m = math.max(m, math.abs(value(n)))
        else { go(lows(n)); go(highs(n)) }
    go(f)
    m

  /** Nombre de nœuds (terminaux + internes) accessibles depuis `f`. */
  def nodeCount(f: Int): Int =
    val seen = scala.collection.mutable.Set.empty[Int]
    def go(n: Int): Unit =
      if seen.add(n) && !isTerminal(n) then { go(lows(n)); go(highs(n)) }
    go(f)
    seen.size
