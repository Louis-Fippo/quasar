package io.quasar.core.dd

/**
 * Diagramme de décision binaire réduit et ordonné (ROBDD) auto-contenu — backend symbolique (§6.4,
 * D2 : pas de dépendance native par défaut).
 *
 * Les variables sont des entiers ; l'ordre du diagramme est l'ordre croissant des indices (plus
 * petit indice décidé en premier). Les terminaux sont `0` (faux) et `1` (vrai). Toutes les
 * opérations sont mémoïsées (table unique + cache `ite`).
 */
final class Bdd:

  // table des nœuds : pour id >= 2, (variable, branche-0, branche-1)
  private val vars = scala.collection.mutable.ArrayBuffer[Int](-1, -1)
  private val lows = scala.collection.mutable.ArrayBuffer[Int](-1, -1)
  private val highs = scala.collection.mutable.ArrayBuffer[Int](-1, -1)
  private val unique = scala.collection.mutable.HashMap.empty[(Int, Int, Int), Int]
  private val iteCache = scala.collection.mutable.HashMap.empty[(Int, Int, Int), Int]

  val False: Int = 0
  val True: Int = 1

  private inline def isTerminal(n: Int): Boolean = n <= 1
  private def varOf(n: Int): Int = if isTerminal(n) then Int.MaxValue else vars(n)
  private def low(n: Int): Int = lows(n)
  private def high(n: Int): Int = highs(n)

  /** Crée (ou réutilise) un nœud réduit `(v -> low | high)`. */
  def mk(v: Int, l: Int, h: Int): Int =
    if l == h then l
    else
      unique.getOrElseUpdate(
        (v, l, h), {
          val id = vars.length
          vars += v; lows += l; highs += h
          id
        }
      )

  /** Variable `v` comme BDD : vrai ssi `v` est vraie. */
  def variable(v: Int): Int = mk(v, False, True)

  /** Littéral `v` (positif) ou `¬v` (négatif). */
  def literal(v: Int, positive: Boolean): Int =
    if positive then mk(v, False, True) else mk(v, True, False)

  /** if-then-else symbolique. */
  def ite(f: Int, g: Int, h: Int): Int =
    if f == True then g
    else if f == False then h
    else if g == True && h == False then f
    else if g == h then g
    else
      iteCache.getOrElseUpdate(
        (f, g, h), {
          val v = math.min(varOf(f), math.min(varOf(g), varOf(h)))
          val (f0, f1) = cofactor(f, v)
          val (g0, g1) = cofactor(g, v)
          val (h0, h1) = cofactor(h, v)
          mk(v, ite(f0, g0, h0), ite(f1, g1, h1))
        }
      )

  private def cofactor(n: Int, v: Int): (Int, Int) =
    if varOf(n) == v then (low(n), high(n)) else (n, n)

  def not(f: Int): Int = ite(f, False, True)
  def and(f: Int, g: Int): Int = ite(f, g, False)
  def or(f: Int, g: Int): Int = ite(f, True, g)
  def xor(f: Int, g: Int): Int = ite(f, not(g), g)
  def iff(f: Int, g: Int): Int = ite(f, g, not(g))
  def andAll(fs: IterableOnce[Int]): Int = fs.iterator.foldLeft(True)(and)
  def orAll(fs: IterableOnce[Int]): Int = fs.iterator.foldLeft(False)(or)

  /** Restriction `f|v=b`. */
  def restrict(f: Int, v: Int, b: Boolean): Int =
    if isTerminal(f) then f
    else if vars(f) > v then f
    else if vars(f) == v then if b then high(f) else low(f)
    else mk(vars(f), restrict(low(f), v, b), restrict(high(f), v, b))

  /** Quantification existentielle `∃v. f`. */
  def existsVar(f: Int, v: Int): Int = or(restrict(f, v, false), restrict(f, v, true))

  /** Quantification existentielle sur un ensemble de variables. */
  def exists(f: Int, vs: Iterable[Int]): Int = vs.foldLeft(f)((acc, v) => existsVar(acc, v))

  /**
   * Renomme les variables via `mapVar`, **supposée strictement monotone sur le support de `f`**
   * (préserve l'ordre du diagramme). Utilisé pour `x' -> x`.
   */
  def relabel(f: Int, mapVar: Int => Int): Int =
    val memo = scala.collection.mutable.HashMap.empty[Int, Int]
    def go(n: Int): Int =
      if isTerminal(n) then n
      else memo.getOrElseUpdate(n, mk(mapVar(vars(n)), go(low(n)), go(high(n))))
    go(f)

  /** Vrai si `f` est satisfiable (≠ faux). */
  def isSat(f: Int): Boolean = f != False

  /** Nombre d'assignations des variables `support` (triées) satisfaisant `f`. */
  def satCount(f: Int, support: List[Int]): Long =
    def go(n: Int, vs: List[Int]): Long = vs match
      case Nil => if n == True then 1L else 0L
      case v :: rest =>
        if varOf(n) == v then go(low(n), rest) + go(high(n), rest)
        else 2L * go(n, rest) // variable absente du diagramme : libre
    go(f, support)

  /** Nombre de nœuds internes accessibles depuis `f` (taille du diagramme). */
  def nodeCount(f: Int): Int =
    val seen = scala.collection.mutable.Set.empty[Int]
    def go(n: Int): Unit =
      if !isTerminal(n) && seen.add(n) then { go(low(n)); go(high(n)) }
    go(f)
    seen.size
