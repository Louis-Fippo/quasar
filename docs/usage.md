# Guide d'utilisation de la CLI `quasar`

Ce guide documente la **surface CLI réellement implémentée**. La spécification
cible (y compris les options encore à venir) est dans `CLAUDE.md §7` ; les écarts
connus sont listés en [fin de page](#options-de-la-spec-non-encore-implémentées).

## Invocation

```bash
# fat-jar (recommandé)
sbt "cli/assembly"                       # -> cli/target/scala-3.3.4/quasar.jar
JAR=cli/target/scala-3.3.4/quasar.jar
java -jar $JAR <groupe> <commande> [args] [options]

# ou en dev, sans packager
sbt "project cli" "run <groupe> <commande> ..."
```

Structure générale : `quasar <groupe> <commande> [arguments] [options]`.

Groupes : `model`, `analyze`, `topology`, `transform`, `solver`, `verify`,
`repo`, `bench`, `tui`.

### Conventions communes

| Élément | Signification |
|---|---|
| `--goal a=j` | objectif : atteindre l'état local `j` de l'automate `a` |
| `--from "b=0,c=1"` | contexte initial (sinon : `initial` du modèle, ou contexte vide) |
| `--json` | sortie machine JSON stable (contrat pour scripts et TUI) |
| `-o <fichier>` | fichier de sortie |

### Options globales

```
-V, --version     affiche la version + signale les adaptateurs externes détectés
-h, --help        aide (disponible sur chaque groupe et commande)
```

> Les options globales `--config`, `--log-level`, `--quiet`, `--threads`,
> `--cache-dir`, `--no-cache` figurent dans la spec mais ne sont pas encore
> câblées dans la couche CLI.

---

## `model` — cycle de vie des modèles

```
model import   <model> [--format <fmt>] [-o <out>]   importe vers l'IR ANX
model export   <model> [--format <fmt>] [-o <out>]   exporte vers un format
model convert  <in> <out>                            import + export (formats déduits)
model validate <model> [--json]                      cohérence (taux>0, niveaux, préconditions)
model info     <model> [--json]                      résumé (#automates, #transitions…)
model inspect  <model>                               détail automates / transitions
model stats    <model> [--json]                      métriques de graphe
model diff     <m1> <m2> [--json]                    différences structurelles
model normalize<model> [--booleanize] [-o <out>]     normalisation
model biolqm   <model> [--json]                      projette en bioLQM, signale les pertes
```

Formats `--format` reconnus : `auto` (défaut, par extension), `an` (Pint),
`maboss` (`.bnd`/`.cfg`), `anx`, `dot`, `nusmv`, `storm-prism`, et via bioLQM :
`sbml-qual`, `boolnet`/`bnet`, `ginml`/`zginml`, `booleannet`.

```bash
java -jar $JAR model info    bench/models/p53-mdm2.bnd
java -jar $JAR model convert bench/models/p53-mdm2.bnd p53.anx
java -jar $JAR model export  p53.anx --format sbml-qual -o p53.sbml
java -jar $JAR model export  p53.anx --format nusmv     -o p53.smv
```

---

## `analyze` — moteur d'analyse

```
analyze reachability <model> --goal <a=j> [--from <ctx>] [--mode oa|ua|both] [--symbolic] [--json]
analyze quantitative <model> --goal <a=j> [--from <ctx>] [--metric prob|delay|both]
                                          [--max-states <N>] [--json]
analyze probability  <model> --goal <a=j> [--from <ctx>] [--threshold <p>]
                                          [--symbolic] [--cegar] [--budget <N>] [--json]
analyze delay        <model> --goal <a=j> [--from <ctx>] [--json]
analyze scenario     <model> --goal <a=j> [--from <ctx>] [--kind most-probable|fastest]
                                          [-k <N>] [--json]
analyze cutsets      <model> --goal <a=j> [--from <ctx>] [--max-size <N>] [--json]
analyze mutations    <model> --goal <a=j> [--from <ctx>] --effect enable|disable [--json]
analyze compare      <model> --goal <a=j> [--from <ctx>] [--json]
```

| Commande | Ce qu'elle calcule | Garantie |
|---|---|---|
| `reachability` | atteignabilité qualitative | `--mode oa` = nécessaire, `ua` = suffisante ; `--symbolic` = **exact** (BDD/MDD) |
| `quantitative` | `P(R)` et/ou délai `T(R)` | `P(R)` exacte (CTMC) si le cône tient sous `--max-states` (défaut 100000), sinon borne inf. sound |
| `probability` | borne inf. ou valeur exacte de `P(R)` | `--threshold p` ⇒ booléen `P(R) ≥ p` ; `--symbolic` = exact (MDD) ; `--cegar` = encadrement sound `[lo,hi]` raffiné par `--budget` (défaut 256) |
| `delay` | délai au plus tôt `T(R)` | borne inf. (accumulation séquentielle le long des chaînes causales) |
| `scenario` | `k` meilleurs scénarios | `--kind most-probable` (Viterbi) ou `fastest` (tropical) ; ordonnés |
| `cutsets` | ensembles de coupe d'intervention | `--max-size` borne la taille (défaut 3) ; coupes effectives |
| `mutations` | mutations gain/perte rendant l'objectif (in)atteignable | via OA/UA |
| `compare` | bornes QUASAR vs oracle exact (cône) | contrôle de justesse côte à côte |

```bash
java -jar $JAR analyze reachability p53.anx --goal p53=1 --mode both
java -jar $JAR analyze reachability cellfate.bnd --goal Apoptosis=1 --symbolic
java -jar $JAR analyze probability  cellfate.bnd --goal Apoptosis=1 --cegar --budget 512
java -jar $JAR analyze probability  cellfate.bnd --goal Apoptosis=1 --threshold 0.1
java -jar $JAR analyze scenario     p53.anx --goal p53=1 --kind fastest -k 3
java -jar $JAR analyze mutations    p53.anx --goal Mdm2=1 --effect disable
```

---

## `topology` — structure et attracteurs

```
topology scc         <model> [--json]                     composantes fortement connexes
topology cycles      <model> [--sign positive|negative|all] [--json]   circuits de Thomas
topology feedback    <model> [--json]                     circuits de rétroaction + signes
topology fixpoints   <model> [--json]                     points fixes (états stables)
topology attractors  <model> [--method exact|abstract] [--json]   attracteurs (exact borné)
topology trap-spaces <model> [--minimal] [--json]         espaces piège
```

```bash
java -jar $JAR topology feedback   p53.anx
java -jar $JAR topology cycles     p53.anx --sign negative
java -jar $JAR topology attractors p53.anx
```

---

## `transform` — transformations de modèle

```
transform reduce     <model> --goal <a=j> [-o <out>]              réduction orientée-but (cône)
transform slice      <model> --component <a> [-o <out>]           tranche autour d'un composant
transform booleanize <model> [-o <out>]                           multivalué -> booléen
transform abstract   <model> --goal <a=j> [--from <ctx>] [--refine cegar] [-o <out>]
                                                                  abstraction-raffinement (CEGAR)
```

`transform abstract … --refine cegar` encadre l'atteignabilité par `OA(V)`/`UA(V)`
sur un ensemble d'automates *visibles* raffiné jusqu'à décision, écrit
optionnellement la sur-approximation.

```bash
java -jar $JAR transform reduce   p53.anx --goal Mdm2=1 -o p53-reduit.anx
java -jar $JAR transform slice    p53.anx --component p53
java -jar $JAR transform abstract cellfate.bnd --goal Apoptosis=1 --refine cegar
```

---

## `solver` — accès bas niveau GLC / ⌈Gω⌉

```
solver glc  <model> --goal <a=j> [--from <ctx>] [-o dot|json]   construit/exporte le GLC
solver qglc <model> --goal <a=j> [--from <ctx>] [-o dot|json]   construit ⌈Gω_ς⌉ + valuations
```

```bash
java -jar $JAR solver glc  p53.anx --goal p53=1 -o dot > glc.dot
java -jar $JAR solver qglc p53.anx --goal p53=1 -o json
```

> Les réglages de solveur de la spec (`--semiring`, `--approx`, `--top-k`,
> `--anytime`, `--timeout`, `--max-levels`, `--cycle-policy`) ne sont pas encore
> exposés ici ; `analyze scenario -k` couvre le top-k.

---

## `verify` — fallback exact / oracle externe

Nécessitent les binaires correspondants dans le `PATH` (détectés à l'exécution ;
échec propre avec message d'installation si absents).

```
verify nusmv    <model> --goal <a=j>     model-checking symbolique qualitatif (NuSMV)
verify storm    <model> --goal <a=j>     vérification probabiliste EXACTE (Storm)
verify maboss   <model> --goal <a=j>     oracle empirique MaBoSS vs borne QUASAR
verify fallback <model> --goal <a=j>     statique puis bascule externe si non concluant
```

```bash
java -jar $JAR verify maboss   cellfate.bnd --goal Apoptosis=1
java -jar $JAR verify storm    p53.anx      --goal p53=1
java -jar $JAR verify fallback p53.anx      --goal p53=1
```

> Les options `--spec <CTL/LTL>`, `--prop <PCTL>`, `--samples`, `--max-time` de la
> spec ne sont pas encore implémentées ; l'objectif passe par `--goal`.

---

## `repo` — dépôt de modèles versionné

```
repo init                                       initialise un dépôt local
repo add    <model> [--tags <t…>] [--id <id>]   ajoute un modèle
repo list   [--tag <t>] [--json]                liste (filtre par tag)
repo get    <id> [-o <out>]                     récupère un modèle
repo rm     <id>                                supprime
repo tag    <id> <tags…>                        ajoute des tags
repo search <query>                             recherche par id/tag
repo bundle <id> -o <out>                       run-bundle reproductible
```

```bash
java -jar $JAR repo init
java -jar $JAR repo add p53.anx --tags reference oscillant
java -jar $JAR repo list --tag reference
java -jar $JAR repo bundle p53 -o p53-bundle.zip
```

---

## `bench` — benchmark & validation

```
bench models                            liste les modèles de référence intégrés
bench validate <model> --goal <a=j> [--json]   pipeline QUASAR vs oracle exact
bench run      <suite>                  exécute une suite (small | all)
```

```bash
java -jar $JAR bench models
java -jar $JAR bench validate cellfate.bnd --goal Apoptosis=1
java -jar $JAR bench run small
```

> La commande `bench report [-o html|md]` et les suites nommées `mapk|tcr|pid` de
> la spec ne sont pas encore implémentées.

---

## `tui` — mode interactif terminal

```
tui [<model>]
```

TUI jline qui **réutilise les mêmes commandes** que la CLI (aucune logique
dupliquée). Le modèle courant est préfixé automatiquement aux commandes qui en
prennent un.

```
quasar> use bench/models/p53-mdm2.bnd
quasar> info
quasar> reachability --goal p53=1
quasar> help
quasar> exit
```

Commandes auto-préfixées avec le modèle courant : `info`, `inspect`, `stats`,
`validate`, `reachability`, `quantitative`, `probability`, `delay`, `scenario`,
`cutsets`, `mutations`, `compare`, `scc`, `cycles`, `feedback`, `fixpoints`,
`attractors`, `trap-spaces`, `reduce`, `slice`.

---

## API Python (`pyquasar`)

Les mêmes capacités sont accessibles depuis Python via la façade JVM
`io.quasar.py.Quasar` (jpype). Voir [Bindings Python](python.md).

---

## Options de la spec non encore implémentées

Récapitulatif des écarts entre `CLAUDE.md §7` (cible) et le code actuel
(45/49 commandes présentes — les manques sont des options avancées) :

- **Global** : `--config`, `--log-level`, `--quiet`, `--threads`, `--cache-dir`,
  `--no-cache`.
- **model normalize** : `--detect-chains`, `--infer-rates`.
- **solver / analyze** : `--semiring`, `--approx`, `--anytime`, `--timeout`,
  `--max-levels`, `--cycle-policy`.
- **verify** : `--spec`, `--prop`, `--samples`, `--max-time`.
- **bench** : commande `report`, suites `mapk|tcr|pid`.
- **dataset** (§7.8) : groupe entier différé (Phase 3).
