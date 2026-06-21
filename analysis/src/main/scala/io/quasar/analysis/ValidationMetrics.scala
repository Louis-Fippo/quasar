package io.quasar.analysis

/**
 * Métriques du rapport de validation consolidé (fiche M2) : justesse et finesse d'une borne, et
 * recouvrement de scénario. Pures et testables ; `bench validate` les assemble avec les valeurs
 * mesurées (borne QUASAR, exact symbolique, oracle MaBoSS).
 */
object ValidationMetrics:

  /** Borne inférieure `binf` confrontée à la valeur `exact`. */
  final case class Bounds(binf: Double, exact: Double):
    /** Justesse (H1) : la borne ne dépasse pas l'exact. */
    def sound(tol: Double = 1e-9): Boolean = binf <= exact + tol

    /** Écart relatif `(exact − binf)/exact` (0 = parfaitement fin), borné à 0 si `exact ≤ 0`. */
    def relGap: Double = if exact <= 0.0 then 0.0 else math.max(0.0, (exact - binf) / exact)

    /** Finesse (H3) `binf/exact` dans `[0, 1]` (1 = borne exacte). */
    def tightness: Double =
      if exact <= 0.0 then 1.0 else math.max(0.0, math.min(1.0, binf / exact))

  /** Recouvrement de Jaccard entre deux ensembles de nœuds (H4) ; 1 si les deux sont vides. */
  def jaccard(a: Set[String], b: Set[String]): Double =
    val union = a | b
    if union.isEmpty then 1.0 else (a & b).size.toDouble / union.size
