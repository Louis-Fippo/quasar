package io.quasar.analysis

import io.quasar.core.dd.Bdd
import io.quasar.core.ir.*

/**
 * Backend symbolique (§6.4) : atteignabilité et points fixes **exacts** par diagrammes de décision
 * (ROBDD), sans énumérer l'espace d'états.
 *
 * Encodage booléen : à l'automate d'indice `a` on associe la variable courante `2a` et la variable
 * suivante `2a+1` (entrelacées, bonnes pour la relation de transition). La relation `T(x, x')` est
 * la disjonction, sur les transitions, de `garde(x) ∧ (x_a' = cible) ∧ frame(autres inchangés)`
 * (sémantique asynchrone). L'ensemble atteignable est le plus petit point fixe de l'image.
 *
 * Réservé aux réseaux booléens (`|S(a)| = 2`) ; les multivalués doivent être booléanisés au
 * préalable.
 */
object Symbolic:

  final case class Result(goalReachable: Boolean, reachableStates: Long, bddNodes: Int)

  def reachability(net: AutomataNetwork, ctx: Context, goal: LocalState): Either[String, Result] =
    requireBoolean(net).map { _ =>
      val g = Encoding(net)
      val reach = g.reachable(ctx)
      val goalSet = g.lit(goal.automaton, goal.level, next = false)
      Result(
        goalReachable = g.bdd.isSat(g.bdd.and(reach, goalSet)),
        reachableStates = g.bdd.satCount(reach, g.currentVars),
        bddNodes = g.bdd.nodeCount(reach)
      )
    }

  /** Nombre de points fixes (états sans transition tirable) — exact, symbolique. */
  def fixpointCount(net: AutomataNetwork): Either[String, Long] =
    requireBoolean(net).map { _ =>
      val g = Encoding(net)
      val hasSucc = g.bdd.exists(g.transition, g.nextVars)
      val deadlock = g.bdd.not(hasSucc)
      g.bdd.satCount(deadlock, g.currentVars)
    }

  private def requireBoolean(net: AutomataNetwork): Either[String, Unit] =
    val mv = net.automata.values.filter(_.levels != 2).map(_.name).toList
    if mv.isEmpty then Right(())
    else
      Left(s"backend symbolique : réseaux booléens uniquement (multivalués : ${mv.mkString(", ")})")

  /** Encodage BDD d'un réseau booléen. */
  private final class Encoding(net: AutomataNetwork):
    val bdd = Bdd()
    private val order = net.ordered.map(_.name).zipWithIndex.toMap
    val currentVars: List[Int] = order.values.toList.sorted.map(2 * _)
    val nextVars: List[Int] = order.values.toList.sorted.map(a => 2 * a + 1)

    private def curVar(a: String): Int = 2 * order(a)
    private def nextVar(a: String): Int = 2 * order(a) + 1

    /** Littéral `automaton = level` sur la couche courante ou suivante. */
    def lit(a: String, level: Int, next: Boolean): Int =
      bdd.literal(if next then nextVar(a) else curVar(a), level == 1)

    /** Relation de transition asynchrone `T(x, x')`. */
    val transition: Int =
      val ts = for au <- net.ordered; t <- au.transitions yield
        val guard = bdd.andAll(
          lit(t.automaton, t.from, next = false) ::
            t.conditions.map(c => lit(c.automaton, c.level, next = false))
        )
        val effect = lit(t.automaton, t.to, next = true)
        val frame = bdd.andAll(net.ordered.filter(_.name != t.automaton).map { b =>
          bdd.iff(bdd.variable(nextVar(b.name)), bdd.variable(curVar(b.name)))
        })
        bdd.and(bdd.and(guard, effect), frame)
      bdd.orAll(ts)

    /** Image asynchrone : états atteignables en un pas depuis `s`. */
    private def image(s: Int): Int =
      val conj = bdd.and(s, transition)
      val ex = bdd.exists(conj, currentVars) // élimine les variables courantes
      bdd.relabel(ex, v => if v % 2 == 1 then v - 1 else v) // x' -> x

    /** État initial encodé depuis le contexte (singletons contraints, libre sinon). */
    private def initial(ctx: Context): Int =
      bdd.andAll(net.ordered.map { au =>
        val levels = ctx.levelsOf(au.name, au.states)
        if levels.size == 1 then lit(au.name, levels.head, next = false) else bdd.True
      })

    /** Plus petit point fixe : ensemble des états atteignables. */
    def reachable(ctx: Context): Int =
      var r = initial(ctx)
      var changed = true
      while changed do
        val next = bdd.or(r, image(r))
        changed = next != r
        r = next
      r
