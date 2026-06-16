package io.quasar.io

import io.quasar.core.ir.*

/**
 * Importeur MaBoSS (`.bnd` + `.cfg` optionnel) — l'oracle de référence (D3).
 *
 * Chaque nœud booléen MaBoSS devient un automate ANX à 2 niveaux `{0, 1}`. La logique d'activation
 * `logic = EXPR` est mise en DNF : chaque clause donne une transition `0 -> 1` (taux `rate_up`)
 * avec les littéraux comme préconditions ; la négation de la logique donne les transitions `1 -> 0`
 * (taux `rate_down`).
 *
 * Les taux symboliques `$param` sont résolus depuis le `.cfg` (défaut 1.0), et `node.istate`
 * fournit le contexte initial.
 */
object MaBossFormat:

  private final case class NodeDef(
      name: String,
      logic: Option[BooleanExpr],
      upParam: Option[String],
      downParam: Option[String]
  )

  /** Importe un `.bnd` (et un `.cfg` optionnel) vers l'IR ANX. */
  def parse(bnd: String, cfg: Option[String] = None): IoResult[AutomataNetwork] =
    for
      nodes <- parseBnd(bnd)
      params = cfg.map(parseParams).getOrElse(Map.empty)
      initCtx = cfg.map(parseInit).getOrElse(Context.empty)
      net <- build(nodes, params, initCtx)
    yield net

  // --- .bnd ----------------------------------------------------------------

  private val nodeBlockRe = """(?s)\bnode\s+(\w+)\s*\{(.*?)\}""".r
  private val logicRe = """(?s)logic\s*=\s*(.*?);""".r
  private val rateUpRe = """(?s)rate_up\s*=\s*(.*?);""".r
  private val rateDownRe = """(?s)rate_down\s*=\s*(.*?);""".r
  private val paramRe = """\$(\w+)""".r

  private def stripComments(s: String): String =
    s.linesIterator
      .map { l =>
        val h = l.indexOf("//")
        if h >= 0 then l.substring(0, h) else l
      }
      .mkString("\n")

  private def parseBnd(text: String): IoResult[List[NodeDef]] =
    val clean = stripComments(text)
    val blocks = nodeBlockRe.findAllMatchIn(clean).toList
    if blocks.isEmpty then Left(IoError("aucun nœud MaBoSS trouvé (node X { ... })"))
    else
      val parsed = blocks.map { m =>
        val name = m.group(1)
        val body = m.group(2)
        val logic = logicRe.findFirstMatchIn(body).map(_.group(1).trim) match
          case None => Right(None)
          case Some(expr) =>
            BooleanExpr
              .parse(expr)
              .left
              .map(e => IoError(s"nœud $name : logique invalide ($e)"))
              .map(Some(_))
        val up = firstParam(rateUpRe.findFirstMatchIn(body).map(_.group(1)))
        val down = firstParam(rateDownRe.findFirstMatchIn(body).map(_.group(1)))
        logic.map(l => NodeDef(name, l, up, down))
      }
      parsed.collectFirst { case Left(e) => e } match
        case Some(e) => Left(e)
        case None => Right(parsed.collect { case Right(v) => v })

  private def firstParam(rate: Option[String]): Option[String] =
    rate.flatMap(r => paramRe.findFirstMatchIn(r).map(_.group(1)))

  // --- .cfg ----------------------------------------------------------------

  private val cfgParamRe = """\$(\w+)\s*=\s*([0-9.eE+-]+)\s*;""".r
  private val istateRe = """(\w+)\.istate\s*=\s*(.*?);""".r

  private def parseParams(cfg: String): Map[String, Double] =
    cfgParamRe
      .findAllMatchIn(stripComments(cfg))
      .flatMap(m => m.group(2).toDoubleOption.map(m.group(1) -> _))
      .toMap

  private def parseInit(cfg: String): Context =
    val pairs = istateRe
      .findAllMatchIn(stripComments(cfg))
      .flatMap { m =>
        val node = m.group(1)
        // formes acceptées : "0", "1", "1 [1] , 0 [0]" -> on prend le 1er entier
        val first = m.group(2).trim.takeWhile(c => c.isDigit || c == '-')
        first.toIntOption.map(node -> _)
      }
      .toList
    Context(pairs.groupMapReduce(_._1)(p => Set(p._2))(_ ++ _))

  // --- construction ANX ----------------------------------------------------

  private def build(
      nodes: List[NodeDef],
      params: Map[String, Double],
      init: Context
  ): IoResult[AutomataNetwork] =
    def rateOf(p: Option[String]): Double =
      p.flatMap(params.get).getOrElse(1.0)

    val automata = nodes.map { nd =>
      val upRate = rateOf(nd.upParam)
      val downRate = rateOf(nd.downParam)
      val transitions = nd.logic match
        case None =>
          // pas de logique : nœud libre (entrée) — aucune transition contrainte
          Nil
        case Some(logic) =>
          val ups = BooleanExpr.dnf(logic).map { clause =>
            Transition(nd.name, 0, 1, clauseToConds(clause), Distribution.Exponential(upRate))
          }
          val downs = BooleanExpr.dnf(BooleanExpr.Not(logic)).map { clause =>
            Transition(nd.name, 1, 0, clauseToConds(clause), Distribution.Exponential(downRate))
          }
          ups ++ downs
      Automaton(nd.name, 2, transitions)
    }

    val meta = Metadata(
      source = Some("maboss"),
      initial = if init.states.isEmpty then None else Some(init)
    )
    Right(AutomataNetwork(automata.map(a => a.name -> a).toMap, meta))

  private def clauseToConds(clause: BooleanExpr.Clause): List[LocalState] =
    clause.toList.sortBy(_._1).map { (name, v) =>
      LocalState(name, if v then 1 else 0)
    }
