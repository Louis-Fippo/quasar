package io.quasar.analysis

import io.quasar.core.Verdict
import io.quasar.core.ir.*

/**
 * Témoin d'atteignabilité (sous-approximation) : une assignation cohérente des automates et la
 * suite (dédupliquée) de transitions à tirer pour la réaliser.
 */
final case class Witness(assignment: Map[String, Int], transitions: List[Transition])

/**
 * Résultat d'une analyse d'atteignabilité qualitative.
 *
 *   - `oaReachable` (sur-approximation) : condition *nécessaire*. Si `false`, le but est assurément
 *     inatteignable.
 *   - `uaReachable` (sous-approximation) : condition *suffisante*. Si `true`, le but est assurément
 *     atteignable, et `witness` donne une assignation témoin.
 */
final case class ReachabilityResult(
    goal: LocalState,
    oaReachable: Boolean,
    uaReachable: Boolean,
    mayReach: Set[LocalState],
    witness: Option[Witness]
):
  /** Verdict combiné à trois valeurs. */
  def verdict: Verdict =
    if uaReachable then Verdict.Reachable
    else if !oaReachable then Verdict.Unreachable
    else Verdict.Inconclusive

/**
 * Atteignabilité statique sans construction de l'espace d'états.
 *
 *   - **OA** : plus petit point fixe monotone des états locaux « possiblement atteignables ».
 *     Sur-approxime l'ensemble réellement atteignable (relâche la simultanéité des préconditions),
 *     donc `goal ∉ mayReach ⇒ inatteignable`.
 *   - **UA** : recherche d'une assignation cohérente (commitment DFS) réalisant le but ; tout
 *     témoin trouvé correspond à une exécution concrète, donc `witness défini ⇒ atteignable`.
 */
object Reachability:

  def analyze(net: AutomataNetwork, ctx: Context, goal: LocalState): ReachabilityResult =
    val may = mayReach(net, ctx)
    val oa = may.contains(goal)
    val witness = if oa then underApprox(net, ctx, goal) else None
    ReachabilityResult(goal, oa, witness.isDefined, may, witness)

  // --- OA : plus petit point fixe monotone ---------------------------------

  /** Ensemble (sur-approximé) des états locaux possiblement atteignables. */
  def mayReach(net: AutomataNetwork, ctx: Context): Set[LocalState] =
    mayReachBlocked(net, ctx, Set.empty)

  /**
   * Variante de [[mayReach]] où les états locaux de `blocked` ne tiennent jamais (jamais
   * ensemencés, jamais ajoutés). Sert au calcul des ensembles de coupe : une transition dont une
   * précondition est bloquée ne peut pas se tirer.
   */
  def mayReachBlocked(
      net: AutomataNetwork,
      ctx: Context,
      blocked: Set[LocalState]
  ): Set[LocalState] =
    var reach: Set[LocalState] =
      net.ordered.flatMap { au =>
        ctx.levelsOf(au.name, au.states).map(LocalState(au.name, _))
      }.toSet -- blocked

    var changed = true
    while changed do
      changed = false
      for t <- net.transitions do
        val tgt = t.target
        if !blocked.contains(tgt) && reach.contains(t.source)
          && t.conditions.forall(reach.contains)
        then
          if !reach.contains(tgt) then
            reach += tgt
            changed = true
    reach

  // --- UA : commitment DFS (assignation cohérente) -------------------------

  /** Borne par défaut sur le nombre d'états explorés par la recherche UA. */
  val DefaultMaxStates: Int = 200_000

  private type Global = Map[String, Int]

  /**
   * Cône d'influence du but : `goal.automaton` et tous ses régulateurs transitifs. L'ensemble est
   * *clos* (les préconditions d'une transition d'un automate du cône portent uniquement sur des
   * automates du cône), donc les automates hors-cône n'influencent jamais la dynamique du cône.
   */
  def cone(net: AutomataNetwork, goal: LocalState): Set[String] =
    var s = Set(goal.automaton)
    var changed = true
    while changed do
      changed = false
      for
        a <- s
        au <- net.automaton(a).toList
        t <- au.transitions
        c <- t.conditions
        if !s.contains(c.automaton)
      do
        s += c.automaton
        changed = true
    s

  /**
   * Cherche un témoin concret d'atteignabilité par recherche en avant EXACTE dans le cône
   * d'influence du but (les automates hors-cône restent figés à leur niveau initial — un
   * comportement valide). Toute exécution trouvée est réalisable dans le système complet ⇒
   * condition suffisante (sound). Bornée à `maxStates` états : au-delà, on renvoie `None` (sound,
   * mais non concluant).
   */
  def underApprox(
      net: AutomataNetwork,
      ctx: Context,
      goal: LocalState,
      maxStates: Int = DefaultMaxStates
  ): Option[Witness] =
    val relevant = cone(net, goal)
    val coneAutos = net.ordered.filter(au => relevant.contains(au.name))
    val coneTrans = coneAutos.flatMap(_.transitions)

    // États initiaux (produit cartésien des niveaux possibles sur le cône).
    val starts: List[Global] =
      coneAutos.foldLeft(List(Map.empty[String, Int])) { (acc, au) =>
        val levels = ctx.levelsOf(au.name, au.states).toList.sorted
        for s <- acc; l <- levels yield s.updated(au.name, l)
      }

    def hit(s: Global): Boolean = s.get(goal.automaton).contains(goal.level)
    def firable(s: Global, t: Transition): Boolean =
      s.get(t.automaton).contains(t.from) &&
        t.conditions.forall(c => s.get(c.automaton).contains(c.level))

    val parent = scala.collection.mutable.Map.empty[Global, (Global, Transition)]
    val seen = scala.collection.mutable.Set.empty[Global]
    val queue = scala.collection.mutable.Queue.empty[Global]
    starts.foreach { s =>
      seen += s; queue.enqueue(s)
    }

    def reconstruct(goalState: Global): List[Transition] =
      val buf = scala.collection.mutable.ListBuffer.empty[Transition]
      var cur = goalState
      while parent.contains(cur) do
        val (prev, t) = parent(cur)
        buf.prepend(t)
        cur = prev
      buf.toList

    starts.find(hit) match
      case Some(s) => Some(Witness(s.view.filterKeys(relevant).toMap, Nil))
      case None =>
        var result: Option[Global] = None
        while queue.nonEmpty && result.isEmpty && seen.size <= maxStates do
          val s = queue.dequeue()
          val it = coneTrans.iterator
          while it.hasNext && result.isEmpty do
            val t = it.next()
            if firable(s, t) then
              val ns = s.updated(t.automaton, t.to)
              if !seen.contains(ns) then
                parent(ns) = (s, t)
                if hit(ns) then result = Some(ns)
                else
                  seen += ns
                  queue.enqueue(ns)
        result.map(g => Witness(g.view.filterKeys(relevant).toMap, reconstruct(g)))
