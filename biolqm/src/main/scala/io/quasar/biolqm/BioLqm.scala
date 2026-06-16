package io.quasar.biolqm

import io.quasar.core.ir.*
import org.colomoto.biolqm.service.LQMServiceManager
import org.colomoto.biolqm.{LogicalModel, NodeInfo}
import org.colomoto.mddlib.PathSearcher

import java.io.File
import java.nio.file.Files
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Projection ANX ↔ bioLQM (D1 : l'IR ANX est isomorphe à bioLQM).
 *
 * **bioLQM → ANX** (import, multivalué) : pour chaque composant, on énumère les chemins de sa
 * fonction logique (MDD) menant à chaque valeur cible `t` via `PathSearcher` ; en sémantique
 * asynchrone unitaire de Thomas, un composant au niveau `v` monte (`v→v+1`) quand `t > v` et
 * descend (`v→v-1`) quand `t < v`, sous les conditions du chemin (régulateurs fixés). Cela débloque
 * l'import SBML-qual, BoolNet, GINML, etc.
 *
 * **ANX → bioLQM** (export, booléen) : la fonction d'activation de chaque composant est la DNF de
 * ses transitions `0→1` ; on passe par le format BoolNet que bioLQM compile en MDD, puis on
 * sauvegarde (SBML-qual…).
 */
object BioLqm:

  /**
   * Enregistrement explicite des formats bioLQM. Le `ServiceLoader` Java ne découvre pas les
   * fournisseurs sous le classloader de sbt/test ; on les enregistre donc à la main (une seule
   * fois).
   */
  private lazy val ensureFormats: Unit =
    val formats = List(
      org.colomoto.biolqm.io.bnet.BNetFormat(),
      org.colomoto.biolqm.io.sbml.SBMLFormat(),
      org.colomoto.biolqm.io.ginml.GINMLFormat(),
      org.colomoto.biolqm.io.booleannet.BooleanNetFormat(),
      org.colomoto.biolqm.io.maboss.MaBoSSFormat(),
      org.colomoto.biolqm.io.functions.BooleanFunctionFormat(),
      org.colomoto.biolqm.io.truthtable.TruthTableFormat()
    )
    formats.foreach(f => Try(LQMServiceManager.register(f)))

  // --- bioLQM -> ANX -------------------------------------------------------

  def fromLogicalModel(lm: LogicalModel): AutomataNetwork =
    val comps = lm.getComponents.asScala.toVector
    val funcs = lm.getLogicalFunctions
    val mgr = lm.getMDDManager
    val vars = mgr.getAllVariables
    val orderToName: Map[Int, String] =
      vars.iterator.map(v => v.order -> v.key.asInstanceOf[NodeInfo].getNodeID).toMap
    val nameToOrder: Map[String, Int] = orderToName.map(_.swap)

    val automata = comps.zipWithIndex.map { (comp, ci) =>
      val name = comp.getNodeID
      val max = comp.getMax.toInt
      val selfOrd = nameToOrder.get(name)
      val root = funcs(ci)
      val transitions = scala.collection.mutable.ListBuffer.empty[Transition]

      for t <- 0 to max do
        val searcher = PathSearcher(mgr, t)
        val path = searcher.setNode(root)
        val it = searcher.iterator()
        while it.hasNext do
          it.next()
          val selfVal = selfOrd.map(path(_)).getOrElse(-1)
          val conds = path.indices.iterator
            .filter(o => path(o) >= 0 && !selfOrd.contains(o))
            .map(o => LocalState(orderToName(o), path(o)))
            .toList
          for v <- 0 until max if t > v && (selfVal == -1 || selfVal == v) do
            transitions += Transition(name, v, v + 1, conds)
          for v <- 1 to max if t < v && (selfVal == -1 || selfVal == v) do
            transitions += Transition(name, v, v - 1, conds)

      Automaton(name, max + 1, transitions.toList)
    }

    AutomataNetwork(automata.map(a => a.name -> a).toMap, Metadata(source = Some("biolqm")))

  /** Identifiant de format bioLQM déduit de l'extension. */
  def formatForPath(path: String): Option[String] =
    val p = path.toLowerCase
    if p.endsWith(".bnet") then Some("bnet")
    else if p.endsWith(".sbml") || p.endsWith(".xml") then Some("sbml")
    else if p.endsWith(".ginml") || p.endsWith(".zginml") then Some("ginml")
    else if p.endsWith(".bnd") then Some("maboss")
    else if p.endsWith(".booleannet") then Some("booleannet")
    else None

  /** Importe un fichier via bioLQM (format déduit de l'extension) puis projette en ANX. */
  def importFile(path: String): Either[String, AutomataNetwork] =
    ensureFormats
    Try {
      val lm = formatForPath(path) match
        case Some(id) => LQMServiceManager.getFormat(id).load(path)
        case None => LQMServiceManager.load(path)
      Option(lm)
    }.toEither.left
      .map(e => s"bioLQM : ${e.getMessage}")
      .flatMap {
        case None => Left(s"bioLQM : échec du chargement de '$path'")
        case Some(lm) => Right(fromLogicalModel(lm))
      }

  // --- ANX -> bioLQM -------------------------------------------------------

  /** Génère le texte BoolNet (`cible, fonction`) d'un réseau booléen. */
  def toBnet(net: AutomataNetwork): Either[String, String] =
    val multivalued = net.automata.values.filter(_.levels != 2).map(_.name).toList
    if multivalued.nonEmpty then
      Left(s"export bioLQM : réseau booléen requis (multivalués : ${multivalued.mkString(", ")})")
    else
      val sb = StringBuilder("targets, factors\n")
      for au <- net.ordered do
        val ups = au.transitions.filter(t => t.from == 0 && t.to == 1)
        val expr =
          if au.transitions.isEmpty then au.name // entrée libre : identité
          else if ups.isEmpty then "0" // jamais activé
          else ups.map(condExpr).mkString(" | ")
        sb ++= s"${au.name}, $expr\n"
      Right(sb.toString)

  private def condExpr(t: Transition): String =
    if t.conditions.isEmpty then "1"
    else
      t.conditions
        .map(c => if c.level == 1 then c.automaton else s"!${c.automaton}")
        .mkString(" & ")

  def toLogicalModel(net: AutomataNetwork): Either[String, LogicalModel] =
    ensureFormats
    toBnet(net).flatMap { bnet =>
      Try {
        val f = File.createTempFile("quasar-", ".bnet")
        f.deleteOnExit()
        Files.writeString(f.toPath, bnet)
        LQMServiceManager.getFormat("bnet").load(f.getAbsolutePath)
      }.toEither.left.map(e => s"bioLQM (bnet) : ${e.getMessage}")
    }

  /** Exporte un réseau ANX via bioLQM dans le format donné (`sbml`, `bnet`, …). */
  def exportFile(net: AutomataNetwork, path: String, formatId: String): Either[String, Unit] =
    toLogicalModel(net).flatMap { lm =>
      Try(LQMServiceManager.save(lm, path, formatId)).toEither.left
        .map(e => s"bioLQM : ${e.getMessage}")
        .flatMap(ok => if ok then Right(()) else Left(s"bioLQM : échec de l'export '$formatId'"))
    }

  // --- diagnostics de projection ------------------------------------------

  /** Signale les pertes éventuelles de la projection ANX → bioLQM. */
  def projectionLoss(net: AutomataNetwork): List[String] =
    val diags = List.newBuilder[String]
    val mv = net.automata.values.filter(_.levels != 2).map(_.name).toList
    if mv.nonEmpty then
      diags += s"composants multivalués non exportables en BoolNet : ${mv.mkString(", ")}"
    // bioLQM logique : pas de taux ; signale si des taux non triviaux existent
    if net.transitions.exists(t => !t.dist.isExponential || t.dist.meanRate != 1.0) then
      diags += "les taux/distributions ne sont pas représentés dans le modèle logique bioLQM"
    diags.result()
