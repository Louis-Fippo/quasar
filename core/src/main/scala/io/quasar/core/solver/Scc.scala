package io.quasar.core.solver

/**
 * Composantes fortement connexes (algorithme de Tarjan), générique sur un type de nœud `N`. Renvoie
 * les SCC en ordre topologique inverse (les puits d'abord).
 */
object Scc:

  def compute[N](nodes: Iterable[N], succ: N => Iterable[N]): List[Set[N]] =
    var index = 0
    val idx = scala.collection.mutable.Map.empty[N, Int]
    val low = scala.collection.mutable.Map.empty[N, Int]
    val onStack = scala.collection.mutable.Set.empty[N]
    val stack = scala.collection.mutable.Stack.empty[N]
    val result = scala.collection.mutable.ListBuffer.empty[Set[N]]

    // pile explicite pour éviter le débordement sur grands graphes
    def strongConnect(root: N): Unit =
      val work = scala.collection.mutable.Stack[(N, Iterator[N])]((root, succ(root).iterator))
      idx(root) = index; low(root) = index; index += 1
      stack.push(root); onStack += root

      while work.nonEmpty do
        val (v, it) = work.top
        if it.hasNext then
          val w = it.next()
          if !idx.contains(w) then
            idx(w) = index; low(w) = index; index += 1
            stack.push(w); onStack += w
            work.push((w, succ(w).iterator))
          else if onStack.contains(w) then low(v) = math.min(low(v), idx(w))
        else
          work.pop()
          // remonte le lowlink au parent
          if work.nonEmpty then
            val parent = work.top._1
            low(parent) = math.min(low(parent), low(v))
          if low(v) == idx(v) then
            val comp = scala.collection.mutable.ListBuffer.empty[N]
            var w = stack.pop(); onStack -= w; comp += w
            while w != v do
              w = stack.pop(); onStack -= w; comp += w
            result += comp.toSet

    for n <- nodes if !idx.contains(n) do strongConnect(n)
    result.toList

  /** SCC non triviales : taille > 1, ou singleton avec auto-boucle. */
  def nonTrivial[N](nodes: Iterable[N], succ: N => Iterable[N]): List[Set[N]] =
    compute(nodes, succ).filter { c =>
      c.size > 1 || c.headOption.exists(n => succ(n).exists(_ == n))
    }
