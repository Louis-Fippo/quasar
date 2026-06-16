package io.quasar.core.dd

/**
 * Diagramme de décision multivalué (MDD) auto-contenu — backend symbolique pour les réseaux
 * **multivalués** (`|S(a)| > 2`, §6.4). Généralise le (MT)BDD : une variable d'indice `v` a
 * `domain(v)` branches, et les terminaux portent une valeur `Double` (0/1 pour les ensembles
 * d'états, réelle pour les probabilités).
 *
 * L'ordre du diagramme est l'ordre croissant des indices de variables. Les domaines des variables
 * sont déclarés via [[setDomain]] avant usage.
 */
final class Mdd:

  private val TERM = Int.MaxValue
  private val varA = scala.collection.mutable.ArrayBuffer.empty[Int]
  private val childrenA = scala.collection.mutable.ArrayBuffer.empty[Array[Int]]
  private val leafValue = scala.collection.mutable.ArrayBuffer.empty[Double]
  private val leafIntern = scala.collection.mutable.HashMap.empty[Double, Int]
  private val unique = scala.collection.mutable.HashMap.empty[(Int, Seq[Int]), Int]
  private val applyCache = scala.collection.mutable.HashMap.empty[(Int, Int, Int), Int]

  val domains = scala.collection.mutable.HashMap.empty[Int, Int]
  def setDomain(v: Int, n: Int): Unit = domains(v) = n

  private def newNode(v: Int, ch: Array[Int], value: Double): Int =
    val id = varA.length
    varA += v; childrenA += ch; leafValue += value
    id

  def constant(d: Double): Int =
    leafIntern.getOrElseUpdate(d, newNode(TERM, Array.empty, d))

  val zero: Int = constant(0.0)
  val one: Int = constant(1.0)

  private def isTerminal(n: Int): Boolean = varA(n) == TERM
  private def varOf(n: Int): Int = varA(n)
  def value(n: Int): Double = leafValue(n)

  /** Crée (ou réutilise) un nœud réduit `(v -> children)`. */
  def node(v: Int, ch: Array[Int]): Int =
    require(v < TERM, "node sur variable terminale")
    if ch.forall(_ == ch(0)) then ch(0)
    else unique.getOrElseUpdate((v, ch.toSeq), newNode(v, ch.clone(), Double.NaN))

  /** Prédicat 0/1 : `1.0` si la variable `v` vaut `level`, `0.0` sinon. */
  def predicate(v: Int, level: Int): Int =
    node(v, Array.tabulate(domains(v))(j => if j == level then one else zero))

  private def cof(n: Int, v: Int, j: Int): Int =
    if !isTerminal(n) && varA(n) == v then childrenA(n)(j) else n

  def apply(op: MtOp, f: Int, g: Int): Int =
    if isTerminal(f) && isTerminal(g) then constant(op(value(f), value(g)))
    else
      applyCache.getOrElseUpdate(
        (op.ordinal, f, g), {
          val v = math.min(varOf(f), varOf(g))
          node(v, Array.tabulate(domains(v))(j => apply(op, cof(f, v, j), cof(g, v, j))))
        }
      )

  def add(f: Int, g: Int): Int = apply(MtOp.Add, f, g)
  def mul(f: Int, g: Int): Int = apply(MtOp.Mul, f, g)
  def sub(f: Int, g: Int): Int = apply(MtOp.Sub, f, g)
  def and(f: Int, g: Int): Int = apply(MtOp.Min, f, g) // ∧ sur indicateurs 0/1
  def or(f: Int, g: Int): Int = apply(MtOp.Max, f, g) // ∨ sur indicateurs 0/1
  def not(f: Int): Int = sub(one, f)
  def andAll(fs: IterableOnce[Int]): Int = fs.iterator.foldLeft(one)(and)
  def orAll(fs: IterableOnce[Int]): Int = fs.iterator.foldLeft(zero)(or)

  /** Restriction `f|v=level`. */
  def restrict(f: Int, v: Int, level: Int): Int =
    if isTerminal(f) || varA(f) > v then f
    else if varA(f) == v then childrenA(f)(level)
    else node(varA(f), childrenA(f).map(restrict(_, v, level)))

  /** Somme d'abstraction `∑_{v} f` sur le domaine de chaque `v`. */
  def abstractSum(f: Int, vs: Iterable[Int]): Int =
    vs.foldLeft(f)((acc, v) => (0 until domains(v)).map(j => restrict(acc, v, j)).reduce(add))

  /** Abstraction par max `∃-like` (pour les ensembles 0/1). */
  def abstractMax(f: Int, vs: Iterable[Int]): Int =
    vs.foldLeft(f)((acc, v) => (0 until domains(v)).map(j => restrict(acc, v, j)).reduce(or))

  /** Renommage de variables (supposé strictement monotone sur le support). */
  def relabel(f: Int, mapVar: Int => Int): Int =
    val memo = scala.collection.mutable.HashMap.empty[Int, Int]
    def go(n: Int): Int =
      if isTerminal(n) then n
      else memo.getOrElseUpdate(n, node(mapVar(varA(n)), childrenA(n).map(go)))
    go(f)

  def eval(f: Int, assign: Int => Int): Double =
    var n = f
    while !isTerminal(n) do n = childrenA(n)(assign(varA(n)))
    value(n)

  def isSat(f: Int): Boolean = f != zero

  def maxAbsLeaf(f: Int): Double =
    val seen = scala.collection.mutable.Set.empty[Int]
    var m = 0.0
    def go(n: Int): Unit =
      if seen.add(n) then
        if isTerminal(n) then m = math.max(m, math.abs(value(n)))
        else childrenA(n).foreach(go)
    go(f)
    m

  def nodeCount(f: Int): Int =
    val seen = scala.collection.mutable.Set.empty[Int]
    def go(n: Int): Unit =
      if seen.add(n) && !isTerminal(n) then childrenA(n).foreach(go)
    go(f)
    seen.size
