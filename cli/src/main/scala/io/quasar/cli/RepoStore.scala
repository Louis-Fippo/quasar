package io.quasar.cli

import io.quasar.core.ir.AutomataNetwork
import io.quasar.io.{AnxFormat, Importer}

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Entrée du dépôt : identifiant + tags. */
final case class RepoEntry(id: String, tags: Set[String])

/**
 * Dépôt de modèles local et versionné (§7.7), adossé au système de fichiers.
 *
 * Disposition : `<root>/models/<id>.anx` pour les modèles, `<root>/index.tsv` pour l'index
 * (`id\ttag1,tag2`). Tout est stocké au format ANX canonique.
 */
final class RepoStore(root: Path):

  private def modelsDir = root.resolve("models")
  private def indexFile = root.resolve("index.tsv")
  private def modelPath(id: String) = modelsDir.resolve(s"$id.anx")

  def exists: Boolean = Files.isDirectory(root)

  def init(): Unit =
    Files.createDirectories(modelsDir)
    if !Files.exists(indexFile) then Files.writeString(indexFile, "")

  def add(net: AutomataNetwork, id: String, tags: Set[String]): Unit =
    Files.createDirectories(modelsDir)
    Files.writeString(modelPath(id), AnxFormat.render(net))
    writeIndex(readIndex().filterNot(_.id == id) :+ RepoEntry(id, tags))

  def list(tag: Option[String] = None): List[RepoEntry] =
    val all = readIndex()
    tag.fold(all)(t => all.filter(_.tags.contains(t)))

  def get(id: String): Option[AutomataNetwork] =
    Option.when(Files.exists(modelPath(id)))(()).flatMap { _ =>
      Importer.fromFile(modelPath(id).toString).toOption
    }

  def remove(id: String): Boolean =
    val existed = Files.deleteIfExists(modelPath(id))
    writeIndex(readIndex().filterNot(_.id == id))
    existed

  def tag(id: String, newTags: Set[String]): Boolean =
    val idx = readIndex()
    if !idx.exists(_.id == id) then false
    else
      writeIndex(idx.map(e => if e.id == id then e.copy(tags = e.tags ++ newTags) else e))
      true

  def search(query: String): List[RepoEntry] =
    val q = query.toLowerCase
    readIndex().filter(e =>
      e.id.toLowerCase.contains(q) || e.tags.exists(_.toLowerCase.contains(q))
    )

  /** Run-bundle reproductible : modèle ANX + en-tête de métadonnées. */
  def bundle(id: String, out: Path): Boolean =
    get(id).exists { net =>
      val tags = readIndex().find(_.id == id).map(_.tags).getOrElse(Set.empty)
      val header = s"# QUASAR run-bundle\n# id: $id\n# tags: ${tags.mkString(",")}\n\n"
      Files.writeString(out, header + AnxFormat.render(net))
      true
    }

  private def readIndex(): List[RepoEntry] =
    if !Files.exists(indexFile) then Nil
    else
      Files.readAllLines(indexFile).asScala.toList.filter(_.trim.nonEmpty).map { line =>
        line.split("\t", -1) match
          case Array(id, tags) => RepoEntry(id, splitTags(tags))
          case Array(id) => RepoEntry(id, Set.empty)
          case _ => RepoEntry(line.trim, Set.empty)
      }

  private def writeIndex(entries: List[RepoEntry]): Unit =
    val text = entries
      .sortBy(_.id)
      .map(e => s"${e.id}\t${e.tags.toList.sorted.mkString(",")}")
      .mkString("\n")
    Files.createDirectories(root)
    Files.writeString(indexFile, text)

  private def splitTags(s: String): Set[String] =
    s.split(",").map(_.trim).filter(_.nonEmpty).toSet

object RepoStore:
  /** Dépôt par défaut : `./.quasar-repo` (surchargé par $QUASAR_REPO). */
  def default(): RepoStore =
    val dir = sys.env.getOrElse("QUASAR_REPO", ".quasar-repo")
    RepoStore(Paths.get(dir))

  def at(path: String): RepoStore = RepoStore(Paths.get(path))

  def idFromPath(path: String): String =
    val name = Paths.get(path).getFileName.toString
    val dot = name.lastIndexOf('.')
    if dot > 0 then name.substring(0, dot) else name

  def ensureWritable(store: RepoStore): Either[String, Unit] =
    Try(store.init()).toEither.left.map(e => s"dépôt inaccessible : ${e.getMessage}")
