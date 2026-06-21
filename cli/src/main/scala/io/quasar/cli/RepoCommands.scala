package io.quasar.cli

import cats.syntax.all.*
import com.monovore.decline.*
import io.quasar.io.AnxFormat

import java.nio.file.Paths

/** Groupe `quasar repo` — dépôt de modèles versionné (§7.7). */
object RepoCommands:

  type Run = () => Int

  private val tagsOpt = Opts.options[String]("tags", "tags").orEmpty
  private def store = RepoStore.default()

  private val initCmd = Opts.subcommand("init", "initialise un dépôt local") {
    Opts.unit.map { _ => () =>
      store.init(); Console.out("dépôt initialisé"); 0
    }
  }

  private val addCmd = Opts.subcommand("add", "ajoute un modèle au dépôt") {
    (Opts.argument[String]("model"), tagsOpt, Opts.option[String]("id", "identifiant").orNone)
      .mapN { (path, tags, idOpt) => () =>
        Console.loadModel(path) match
          case Left(c) => c
          case Right(net) =>
            val id = idOpt.getOrElse(RepoStore.idFromPath(path))
            store.add(net, id, tags.toSet)
            Console.out(s"ajouté : $id"); 0
      }
  }

  private val listCmd = Opts.subcommand("list", "liste les modèles") {
    (Opts.option[String]("tag", "filtre par tag").orNone, Opts.flag("json", "JSON").orFalse)
      .mapN { (tag, json) => () =>
        if !store.exists then Console.fail("dépôt non initialisé (quasar repo init)")
        else
          val es = store.list(tag)
          if Console.jsonEnabled(json) then
            Console.emitJson(
              Json.arr(
                es.map(e =>
                  Json.obj(
                    "id" -> Json.str(e.id),
                    "tags" -> Json.arr(e.tags.toList.sorted.map(Json.str))
                  )
                )
              )
            )
          else if es.isEmpty then Console.out("(dépôt vide)")
          else es.foreach(e => Console.out(f"${e.id}%-24s ${e.tags.toList.sorted.mkString(", ")}"))
          0
      }
  }

  private val getCmd = Opts.subcommand("get", "récupère un modèle") {
    (Opts.argument[String]("id"), Opts.option[String]("output", "fichier", short = "o").orNone)
      .mapN { (id, out) => () =>
        store.get(id) match
          case None => Console.fail(s"modèle inconnu : $id")
          case Some(net) =>
            out match
              case Some(o) =>
                io.quasar.io.Exporter.toFile(net, o, Some(io.quasar.io.Format.Anx)) match
                  case Right(_) => Console.out(s"écrit -> $o"); 0
                  case Left(e) => Console.fail(e.toString)
              case None => Console.out(AnxFormat.render(net)); 0
      }
  }

  private val rmCmd = Opts.subcommand("rm", "supprime un modèle") {
    Opts.argument[String]("id").map { id => () =>
      if store.remove(id) then { Console.out(s"supprimé : $id"); 0 }
      else Console.fail(s"modèle inconnu : $id")
    }
  }

  private val tagCmd = Opts.subcommand("tag", "ajoute des tags") {
    (Opts.argument[String]("id"), Opts.arguments[String]("tags")).mapN { (id, tags) => () =>
      if store.tag(id, tags.toList.toSet) then { Console.out(s"taggé : $id"); 0 }
      else Console.fail(s"modèle inconnu : $id")
    }
  }

  private val searchCmd = Opts.subcommand("search", "recherche par id/tag") {
    Opts.argument[String]("query").map { q => () =>
      val es = store.search(q)
      if es.isEmpty then Console.out("(aucun résultat)")
      else es.foreach(e => Console.out(s"${e.id}  ${e.tags.toList.sorted.mkString(", ")}"))
      0
    }
  }

  private val bundleCmd = Opts.subcommand("bundle", "run-bundle reproductible") {
    (Opts.argument[String]("id"), Opts.option[String]("output", "fichier", short = "o"))
      .mapN { (id, out) => () =>
        if store.bundle(id, Paths.get(out)) then { Console.out(s"bundle -> $out"); 0 }
        else Console.fail(s"modèle inconnu : $id")
      }
  }

  val command: Opts[Run] =
    initCmd
      .orElse(addCmd)
      .orElse(listCmd)
      .orElse(getCmd)
      .orElse(rmCmd)
      .orElse(tagCmd)
      .orElse(searchCmd)
      .orElse(bundleCmd)
