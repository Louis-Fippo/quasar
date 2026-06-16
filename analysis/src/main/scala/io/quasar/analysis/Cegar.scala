package io.quasar.analysis

import io.quasar.core.Verdict
import io.quasar.core.glc.Cone
import io.quasar.core.ir.*

/**
 * Résultat CEGAR : verdict d'atteignabilité, ensemble d'automates *visibles* à la décision, nombre
 * de tours de raffinement, et taille du cône d'influence.
 */
final case class CegarResult(
    verdict: Verdict,
    visible: Set[String],
    rounds: Int,
    coneSize: Int
)

/**
 * Atteignabilité par abstraction-raffinement guidé par contre-exemple (CEGAR, §7.4).
 *
 * On encadre l'atteignabilité concrète par deux abstractions exactes sur un ensemble d'automates
 * *visibles* `V` (contenant le but) :
 *
 *   - **OA(V)** : sous-réseau sur `V`, préconditions hors-`V` abandonnées (sur-approximation) → si
 *     le but y est inatteignable, il l'est concrètement.
 *   - **UA(V)** : sous-réseau sur `V`, transitions à précondition hors-`V` supprimées, automates
 *     hors-`V` figés (sous-approximation) → si le but y est atteignable, il l'est concrètement.
 *
 * Si les deux divergent, on **raffine** : on ajoute à `V` les automates des préconditions
 * abandonnées (le contre-exemple). `V` croît de façon monotone, bornée par le cône d'influence où
 * `OA(V) = UA(V) = exact` ; CEGAR termine donc et décide toujours. L'intérêt : décider souvent avec
 * `|V| ≪ |cône|`.
 */
object Cegar:

  def reachability(net: AutomataNetwork, ctx: Context, goal: LocalState): CegarResult =
    val cone = Cone.of(net, goal.automaton)

    def exactReach(sub: AutomataNetwork): Boolean =
      SymbolicMdd.reachability(sub, restrictCtx(ctx, sub), goal).goalReachable

    var visible = Set(goal.automaton)
    var rounds = 0
    while true do
      val oa = exactReach(overApprox(net, visible))
      if !oa then return CegarResult(Verdict.Unreachable, visible, rounds, cone.size)
      val ua = exactReach(underApprox(net, visible))
      if ua then return CegarResult(Verdict.Reachable, visible, rounds, cone.size)
      // inconclusif : raffiner avec les automates des préconditions abandonnées
      val dropped = droppedRegulators(net, visible)
      if dropped.isEmpty then
        // V ⊇ cône : OA et UA coïncident ; oa décide
        return CegarResult(
          if oa then Verdict.Reachable else Verdict.Unreachable,
          visible,
          rounds,
          cone.size
        )
      visible = visible ++ dropped
      rounds += 1
    // inatteignable (boucle while(true) terminée par return)
    CegarResult(Verdict.Inconclusive, visible, rounds, cone.size)

  // --- abstractions --------------------------------------------------------

  /** Sous-réseau sur `visible`, préconditions hors-`visible` abandonnées (OA). */
  def overApprox(net: AutomataNetwork, visible: Set[String]): AutomataNetwork =
    val automata = net.automata.view
      .filterKeys(visible)
      .map { (name, au) =>
        name -> au.copy(transitions =
          au.transitions.map(t =>
            t.copy(conditions = t.conditions.filter(c => visible.contains(c.automaton)))
          )
        )
      }
      .toMap
    net.copy(automata = automata)

  /** Sous-réseau sur `visible`, transitions à précondition hors-`visible` supprimées (UA). */
  def underApprox(net: AutomataNetwork, visible: Set[String]): AutomataNetwork =
    val automata = net.automata.view
      .filterKeys(visible)
      .map { (name, au) =>
        name -> au.copy(transitions =
          au.transitions.filter(_.conditions.forall(c => visible.contains(c.automaton)))
        )
      }
      .toMap
    net.copy(automata = automata)

  /** Automates apparaissant dans une précondition abandonnée d'un automate visible. */
  private def droppedRegulators(net: AutomataNetwork, visible: Set[String]): Set[String] =
    (for
      a <- visible
      au <- net.automaton(a).toList
      t <- au.transitions
      c <- t.conditions
      if !visible.contains(c.automaton)
    yield c.automaton).toSet

  private def restrictCtx(ctx: Context, sub: AutomataNetwork): Context =
    Context(ctx.states.view.filterKeys(sub.automata.keySet).toMap)
