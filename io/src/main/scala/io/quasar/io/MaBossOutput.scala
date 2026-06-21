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

  /**
   * Série temporelle du probtraj : pour chaque pas de temps, la distribution d'états. Base des
   * **temps d'atteinte** (H2) et du **recouvrement de trajectoire** (H4) — fiche V1.
   */
  final case class Series(steps: Vector[(Double, Distribution)]):
    /** Grille de temps. */
    def times: Vector[Double] = steps.map(_._1)

    /** Probabilité marginale que `node` soit actif à chaque pas de temps. */
    def marginal(node: String): Vector[Double] = steps.map(_._2.probActive(node))

    /** Probabilité que `node` soit actif au temps final. */
    def finalProb(node: String): Double =
      steps.lastOption.map(_._2.probActive(node)).getOrElse(0.0)

    /**
     * Premier temps où `P(node actif) ≥ level` (proxy du premier passage). `None` si le seuil n'est
     * jamais atteint sur l'horizon simulé.
     */
    def firstPassage(node: String, level: Double): Option[Double] =
      steps.collectFirst { case (t, d) if d.probActive(node) >= level => t }

    /** Quantiles de temps d'atteinte : pour `q`, le 1er temps où la CDF marginale ≥ `q`. */
    def quantiles(node: String, qs: Seq[Double]): Vector[(Double, Option[Double])] =
      qs.toVector.map(q => q -> firstPassage(node, q))

    /** Tous les nœuds apparaissant dans la série. */
    def nodes: Set[String] = steps.iterator.flatMap(_._2.states.keysIterator.flatten).toSet

    /**
     * Nœuds activés le long de la trajectoire dominante, avec leur temps de 1ère activation
     * (`P(actif) ≥ level`) — support du recouvrement de scénario (H4).
     */
    def activationTimes(level: Double): Map[String, Double] =
      nodes.flatMap(n => firstPassage(n, level).map(n -> _)).toMap

  /** Parse toute la série temporelle (toutes les lignes de données) du probtraj (fiche V1). */
  def parseSeries(text: String): IoResult[Series] =
    val lines = text.linesIterator.filter(_.trim.nonEmpty).toList
    lines match
      case Nil => Left(IoError("probtraj vide"))
      case header :: rest =>
        val cols = header.split("\t", -1).map(_.trim)
        val firstStat = cols.indexWhere(_.equalsIgnoreCase("State"))
        if firstStat < 0 then Left(IoError("colonne 'State' absente du probtraj"))
        else if rest.isEmpty then Left(IoError("probtraj sans ligne de données"))
        else
          val parsed = rest.map { row =>
            val time = row.split("\t", -1).headOption.flatMap(_.trim.toDoubleOption).getOrElse(0.0)
            parseRow(row, firstStat).map(d => time -> d)
          }
          parsed.collectFirst { case Left(e) => e } match
            case Some(e) => Left(e)
            case None => Right(Series(parsed.collect { case Right(s) => s }.toVector))

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
