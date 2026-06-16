package io.quasar.core.ir

/**
 * Métadonnées non sémantiques d'un modèle : provenance, notes, contexte initial recommandé (issu
 * par ex. d'un `.cfg` MaBoSS).
 */
final case class Metadata(
    name: Option[String] = None,
    source: Option[String] = None,
    initial: Option[Context] = None,
    notes: Map[String, String] = Map.empty
)

object Metadata:
  val empty: Metadata = Metadata()
