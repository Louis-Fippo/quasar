package io.quasar.core.ir

/**
 * Réseau d'automates ANX — l'IR centrale de QUASAR (D1, isomorphe à bioLQM).
 *
 * Un réseau est un ensemble d'automates indexés par nom, plus des métadonnées. Les transitions
 * vivent dans les automates ; les accès agrégés sont fournis ici.
 */
final case class AutomataNetwork(
    automata: Map[String, Automaton],
    metadata: Metadata = Metadata.empty
):

  /** Nom du réseau (métadonnée, ou « anonymous »). */
  def name: String = metadata.name.getOrElse("anonymous")

  /** Liste ordonnée des automates (par nom). */
  def ordered: Vector[Automaton] = automata.values.toVector.sortBy(_.name)

  /** Toutes les transitions du réseau. */
  def transitions: Vector[Transition] = ordered.flatMap(_.transitions)

  /** Nombre d'automates. */
  def size: Int = automata.size

  /** Somme des tailles d'espaces locaux `Σ_a |S(a)|`. */
  def localStateCount: Int = automata.values.map(_.levels).sum

  /** Range des niveaux d'un automate (vide si inconnu). */
  def levelsOf(a: String): Range = automata.get(a).map(_.states).getOrElse(0 until 0)

  /** Récupère un automate par nom. */
  def automaton(a: String): Option[Automaton] = automata.get(a)

  /**
   * Liste des automates qui contraignent `a` (apparaissent dans les préconditions des transitions
   * de `a`) — utile pour la structure de dépendance / la réduction orientée-but.
   */
  def regulatorsOf(a: String): Set[String] =
    automata.get(a) match
      case None => Set.empty
      case Some(au) =>
        au.transitions.flatMap(_.conditions.map(_.automaton)).toSet - a

object AutomataNetwork:
  val empty: AutomataNetwork = AutomataNetwork(Map.empty)

  /** Construit un réseau à partir d'une liste d'automates. */
  def of(automata: Automaton*): AutomataNetwork =
    AutomataNetwork(automata.map(a => a.name -> a).toMap)
