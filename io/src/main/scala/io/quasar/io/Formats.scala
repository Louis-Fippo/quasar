package io.quasar.io

import io.quasar.core.ir.AutomataNetwork

import java.nio.file.{Files, Path, Paths}
import scala.util.Try

/** Formats d'entrée/sortie reconnus. */
enum Format:
  case Anx, MaBoss, An, SbmlQual, BoolNet, Nusmv, StormPrism, Dot

object Format:
  def fromExtension(path: String): Option[Format] =
    val lower = path.toLowerCase
    if lower.endsWith(".anx") then Some(Anx)
    else if lower.endsWith(".bnd") || lower.endsWith(".cfg") then Some(MaBoss)
    else if lower.endsWith(".an") then Some(An)
    else if lower.endsWith(".sbml") || lower.endsWith(".xml") then Some(SbmlQual)
    else if lower.endsWith(".bnet") then Some(BoolNet)
    else if lower.endsWith(".smv") then Some(Nusmv)
    else if lower.endsWith(".prism") || lower.endsWith(".pm") then Some(StormPrism)
    else if lower.endsWith(".dot") then Some(Dot)
    else None

  def parse(name: String): Option[Format] = name.toLowerCase match
    case "anx" => Some(Anx)
    case "maboss" | "bnd" => Some(MaBoss)
    case "an" | "pint" => Some(An)
    case "sbml-qual" | "sbml" => Some(SbmlQual)
    case "boolnet" | "bnet" => Some(BoolNet)
    case "nusmv" => Some(Nusmv)
    case "storm-prism" | "prism" => Some(StormPrism)
    case "dot" => Some(Dot)
    case _ => None

/** Point d'entrée d'import : lecture de fichier + détection de format. */
object Importer:

  /** Importe depuis un fichier ; `format = None` => auto-détection par extension. */
  def fromFile(path: String, format: Option[Format] = None): IoResult[AutomataNetwork] =
    val fmt = format.orElse(Format.fromExtension(path))
    fmt match
      case None => Left(IoError(s"format indéterminé pour '$path' (préciser --format)"))
      case Some(f) => readAll(path).flatMap(text => parseAs(f, text, path))

  private def parseAs(f: Format, text: String, path: String): IoResult[AutomataNetwork] =
    f match
      case Format.Anx => AnxFormat.parse(text)
      case Format.MaBoss => parseMaBoss(path, text)
      case Format.An => AnFormat.parse(text)
      case other => Left(IoError(s"import non supporté pour le format $other"))

  /** MaBoSS : si on importe un `.bnd`, on tente de joindre le `.cfg` voisin. */
  private def parseMaBoss(path: String, text: String): IoResult[AutomataNetwork] =
    val lower = path.toLowerCase
    if lower.endsWith(".cfg") then
      Left(IoError("importer le .bnd MaBoSS (le .cfg est joint automatiquement)"))
    else
      val cfgPath = path.replaceAll("(?i)\\.bnd$", ".cfg")
      val cfg = Try(
        if Files.exists(Paths.get(cfgPath)) then Some(readRaw(cfgPath)) else None
      ).toOption.flatten
      MaBossFormat.parse(text, cfg)

  private def readAll(path: String): IoResult[String] =
    Try(readRaw(path)).toEither.left.map(e => IoError(s"lecture impossible : ${e.getMessage}"))

  private def readRaw(path: String): String =
    Files.readString(Paths.get(path))

/** Point d'entrée d'export : rendu + écriture de fichier. */
object Exporter:

  def render(net: AutomataNetwork, format: Format): IoResult[String] =
    format match
      case Format.Anx => Right(AnxFormat.render(net))
      case Format.An => Right(AnFormat.render(net))
      case Format.Dot => Right(DotFormat.render(net))
      case Format.Nusmv => Right(NusmvFormat.render(net))
      case Format.StormPrism => Right(StormFormat.render(net))
      case other => Left(IoError(s"export non supporté pour le format $other"))

  def toFile(net: AutomataNetwork, path: String, format: Option[Format] = None): IoResult[Unit] =
    val fmt = format.orElse(Format.fromExtension(path))
    fmt match
      case None => Left(IoError(s"format indéterminé pour '$path' (préciser --format)"))
      case Some(f) =>
        render(net, f).flatMap { text =>
          Try(Files.writeString(writePath(path), text)).toEither
            .map(_ => ())
            .left
            .map(e => IoError(s"écriture impossible : ${e.getMessage}"))
        }

  private def writePath(path: String): Path =
    val p = Paths.get(path)
    Option(p.getParent).foreach(d => if !Files.exists(d) then Files.createDirectories(d))
    p
