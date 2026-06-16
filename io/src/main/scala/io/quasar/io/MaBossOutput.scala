package io.quasar.io

/**
 * Parseur de la sortie `probtraj` de MaBoSS (oracle empirique, D3).
 *
 * Le fichier `*_probtraj.csv` est tabulé : les 4+ premières colonnes décrivent le temps/entropie,
 * puis des triplets `(State, Proba, ErrorProba)`. Un « State » est l'ensemble des nœuds actifs
 * joints par `" -- "` (`<nil>` = aucun). On extrait la distribution d'états au **dernier pas de
 * temps** et la probabilité marginale d'activation de chaque nœud.
 */
object MaBossOutput:

  /** Distribution d'états (ensembles de nœuds actifs -> probabilité) au temps final. */
  final case class Distribution(states: Map[Set[String], Double]):
    /** Probabilité marginale que `node` soit actif (=1) au temps final. */
    def probActive(node: String): Double =
      states.iterator.collect { case (s, p) if s.contains(node) => p }.sum

  def parseProbtraj(text: String): IoResult[Distribution] =
    val lines = text.linesIterator.filter(_.trim.nonEmpty).toList
    lines match
      case Nil => Left(IoError("probtraj vide"))
      case header :: rest =>
        val cols = header.split("\t", -1).map(_.trim)
        val firstStat = cols.indexWhere(_.equalsIgnoreCase("State"))
        if firstStat < 0 then Left(IoError("colonne 'State' absente du probtraj"))
        else if rest.isEmpty then Left(IoError("probtraj sans ligne de données"))
        else parseRow(rest.last, firstStat)

  private def parseRow(row: String, firstStat: Int): IoResult[Distribution] =
    val cells = row.split("\t", -1).map(_.trim)
    val acc = scala.collection.mutable.Map.empty[Set[String], Double]
    var i = firstStat
    var error: Option[IoError] = None
    while i + 1 < cells.length && error.isEmpty do
      val stateStr = cells(i)
      val probStr = cells(i + 1)
      if stateStr.nonEmpty then
        probStr.toDoubleOption match
          case Some(p) => acc.updateWith(parseState(stateStr))(o => Some(o.getOrElse(0.0) + p))
          case None => error = Some(IoError(s"probabilité non numérique : '$probStr'"))
      i += 3 // (State, Proba, ErrorProba)
    error.toLeft(Distribution(acc.toMap))

  /** `"Survival -- NFkB"` -> Set(Survival, NFkB) ; `"<nil>"` -> ∅. */
  private def parseState(s: String): Set[String] =
    if s == "<nil>" then Set.empty
    else s.split(" -- ").map(_.trim).filter(n => n.nonEmpty && n != "<nil>").toSet
