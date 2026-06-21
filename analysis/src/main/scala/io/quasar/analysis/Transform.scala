package io.quasar.analysis

import io.quasar.core.ir.*

import scala.util.Random

/**
 * Transformations de modèle (§7.4) préservant les propriétés utiles.
 */
object Transform:

  /**
   * Politique d'assignation de taux pour la valuation des modèles qualitatifs (voie 7.7b, fiche
   * P1). Les modèles importés sans cinétique (SBML-qual, BoolNet, GINML…) reçoivent des taux par
   * défaut peu informatifs ; cette politique les (re)fixe explicitement.
   */
  enum RatePolicy:
    /** Taux unitaire : toutes les transitions à `Exponential(1.0)`. */
    case Unit

    /**
     * Échantillonnage log-uniforme dans `[lo, hi]` (taux > 0), déterministe pour une graine donnée
     * — utile pour l'analyse de sensibilité.
     */
    case Sample(lo: Double, hi: Double)

  /**
   * Assigne une distribution **exponentielle** à chaque transition selon `policy` (fiche P1). Les
   * taux produits sont strictement positifs (cohérents avec [[Validation]]). Le parcours suit un
   * ordre déterministe (automates triés par nom, puis ordre de déclaration des transitions), de
   * sorte que `Sample` soit reproductible pour une `seed` donnée. La structure du réseau
   * (automates, niveaux, préconditions) est inchangée ; seules les distributions sont remplacées.
   */
  def assignRates(net: AutomataNetwork, policy: RatePolicy, seed: Long = 0L): AutomataNetwork =
    policy match
      case RatePolicy.Unit =>
        mapDistributionsOrdered(net)(_ => Distribution.Exponential(1.0))
      case RatePolicy.Sample(lo, hi) =>
        require(
          lo > 0.0 && hi >= lo,
          s"intervalle de taux invalide : [$lo, $hi] (attendu 0 < lo ≤ hi)"
        )
        val rng = new Random(seed)
        val logLo = math.log(lo)
        val span = math.log(hi) - logLo
        mapDistributionsOrdered(net) { _ =>
          Distribution.Exponential(math.exp(logLo + rng.nextDouble() * span))
        }

  /** Remplace la distribution de chaque transition, dans un ordre déterministe. */
  private def mapDistributionsOrdered(
      net: AutomataNetwork
  )(f: Transition => Distribution): AutomataNetwork =
    val updated = net.ordered.map { au =>
      au.name -> au.copy(transitions = au.transitions.map(t => t.copy(dist = f(t))))
    }.toMap
    net.copy(automata = updated)

  /**
   * Réduction orientée-but : restreint le réseau au **cône d'influence** du but (l'automate cible
   * et ses régulateurs transitifs). Le cône étant clos, l'atteignabilité du but est préservée
   * (CLAUDE.md §10).
   */
  def reduce(net: AutomataNetwork, goal: LocalState): AutomataNetwork =
    restrict(net, Reachability.cone(net, goal))

  /**
   * Tranche autour d'un composant : sous-réseau formé du composant et de ses régulateurs
   * transitifs.
   */
  def slice(net: AutomataNetwork, component: String): AutomataNetwork =
    restrict(net, structuralCone(net, component))

  /** Fermeture transitive des régulateurs de `start` (inclus). */
  def structuralCone(net: AutomataNetwork, start: String): Set[String] =
    var s = Set(start)
    var changed = true
    while changed do
      changed = false
      for a <- s; r <- net.regulatorsOf(a) if !s.contains(r) do
        s += r; changed = true
    s

  /** Restreint le réseau aux automates de `keep` (transitions internes conservées). */
  def restrict(net: AutomataNetwork, keep: Set[String]): AutomataNetwork =
    val automata = net.automata.view
      .filterKeys(keep)
      .mapValues(au =>
        au.copy(transitions =
          au.transitions.filter(_.conditions.forall(c => keep.contains(c.automaton)))
        )
      )
      .toMap
    net.copy(automata = automata)

  /**
   * Expansion phase-type (D4) : remplace chaque transition à distribution non triviale
   * (`Erlang`/`PhaseType`, `k > 1` phases) par une **chaîne d'états fantômes** exponentiels `i → φ₁
   * → … → φ_{k-1} → j`, de taux les taux de phase. Seule la première sous-transition porte la
   * précondition (et son taux est celui de la 1ère phase), ce qui rend la compétition CTMC exacte.
   * Les réseaux purement exponentiels sont renvoyés inchangés (coût nul, CLAUDE.md §10).
   */
  def expandPhaseType(net: AutomataNetwork): AutomataNetwork =
    if net.transitions.forall(_.dist.phaseCount <= 1) then net
    else
      val automata = net.automata.map { (name, au) =>
        var nextLevel = au.levels
        val expanded = au.transitions.flatMap { t =>
          val rates = t.dist.phaseRates
          if rates.sizeIs <= 1 then List(t)
          else
            val phantoms = List.fill(rates.size - 1) { val l = nextLevel; nextLevel += 1; l }
            val chain = t.from :: phantoms ::: List(t.to)
            rates.indices.toList.map { m =>
              Transition(
                name,
                chain(m),
                chain(m + 1),
                if m == 0 then t.conditions else Nil,
                Distribution.Exponential(rates(m))
              )
            }
        }
        name -> au.copy(levels = nextLevel, transitions = expanded)
      }
      net.copy(automata = automata)

  /**
   * Booléanisation : vérifie que le réseau est déjà booléen (tous |S(a)| = 2). Les réseaux
   * multivalués nécessitent l'expansion en escalier (Van Ham), prévue en Phase 2 ; un `Left`
   * explicite est renvoyé en attendant.
   */
  def booleanize(net: AutomataNetwork): Either[String, AutomataNetwork] =
    val multivalued = net.automata.values.filter(_.levels > 2).map(_.name).toList
    if multivalued.isEmpty then Right(net)
    else
      Left(
        s"booléanisation multivaluée non supportée pour : ${multivalued.mkString(", ")} " +
          "(expansion Van Ham — Phase 2)"
      )
