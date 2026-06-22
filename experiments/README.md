# Validation du chapitre 4 — notebook expérimental

Ce dossier contient l'expérimentation qui **pilote QUASAR** pour valider
empiriquement les contributions du chapitre 4 (justesse, finesse, scénarios,
scalabilité). Brief complet : `../PLAN_EXPERIMENTAL_validation_chap4.md`.

## Fichiers

| Fichier | Rôle |
|---|---|
| `validation_chap4.ipynb` | notebook structuré par le plan §4 (H1–H6, focus petits modèles + scalabilité) |
| `_build_notebook.py` | **générateur** de `validation_chap4.ipynb` |
| `validation_all_models.ipynb` | notebook **tous modèles** (cohérent avec `retest_all.py`) : matrice H1/H3/H6 + H5 sur les 7 modèles |
| `_build_notebook_all.py` | **générateur** de `validation_all_models.ipynb` |
| `retest_all.py` | harnais CLI de re-test H1–H6 (sortie texte, même logique que le notebook tous-modèles) |
| `figures/`, `figures_all/` | PNG des figures + données CSV |
| `validation_chap4.bundle` | run-bundle reproductible (modèle + tags) |
| `acquire_models.sh` | acquisition des modèles externes (GINsim → SBML-qual) |
| `external/PROVENANCE.md` | provenance/citations des modèles externes (non versionnés) |

> Le notebook est *généré* : pour le modifier, éditer `_build_notebook.py` puis
> `python _build_notebook.py`. Cela garantit qu'aucun résultat n'est saisi à la
> main.

## Prérequis

- **JDK 21** + le fat-jar QUASAR :
  ```bash
  cd .. && sbt "cli/assembly"     # -> cli/target/scala-3.3.4/quasar.jar
  ```
  Le notebook auto-détecte le jar (ou variable `QUASAR_JAR`).
- **Python 3.9+** : `pandas`, `numpy`, `matplotlib`, `nbformat`, `jupyter`.
- *(optionnel)* **MaBoSS** et/ou **Storm** dans le `PATH`. S'ils sont absents,
  les cellules oracle externe sont `SKIPPED` (jamais inventées) ; la justesse H1
  est alors testée contre l'**oracle exact interne** de QUASAR (CTMC/MDD + BFS du
  cône). Sur l'image **CoLoMoTo / Docker**, les deux oracles sont présents.

## Relancer

```bash
# régénérer puis exécuter
python _build_notebook.py
jupyter nbconvert --to notebook --execute --inplace \
    --ExecutePreprocessor.timeout=300 validation_chap4.ipynb

# ou interactivement
jupyter lab validation_chap4.ipynb
```

Les cellules sont **idempotentes** (mémoïsation des appels QUASAR au niveau
notebook ; ré-exécution sans effet de bord). Graines et paramètres
(`SEED`, `N_SAMPLES`, `MAX_TIME`, `TIMEOUT`) sont fixés en Section 0.

## Résultats (environnement sans oracle externe)

| Hypothèse | Statut |
|---|---|
| **H1 — justesse (proba)** | ✅ VALIDÉ — assertion dure `binf ≤ exact` (oracle interne BFS + MDD) ; échoue bruyamment si violée |
| **H2 — justesse (délai)** | ✅ PRÊT (fiche V1 livrée) — T(R) vs quantiles de temps d'atteinte MaBoSS ; exécuté si MaBoSS présent, sinon SKIPPED |
| **H3 — finesse** | ✅ VALIDÉ — écart nul (P(R) calculée exactement sur ces modèles) |
| **H4 — scénarios** | ✅ PRÊT (fiche V1 livrée) — recouvrement Jaccard scénario QUASAR ↔ nœuds activés MaBoSS ; SKIPPED si MaBoSS absent |
| **H5 — scalabilité** | ✅ VALIDÉ — modèles réels 40/65/101 (`bench sweep`) : reachability globale explose à 101 ; l'analyse par cône y reste tractable (H5b) |
| **H6 — optimisations** | ✅ VALIDÉ — convergence anytime + ablation CTMC/MDD/CEGAR (temps & concordance, fiche A3) |

## Modules QUASAR à arbitrer (Section 7 du notebook)

L'expérience a révélé des capacités requises absentes de la CLI ; **toutes ont
été implémentées** (P0 → M2). Historique et statut :

| Id | Proposition | Débloque |
|---|---|---|
| **P0** | `pyquasar` (façade Python) — **livré** ; on garde le pilotage CLI par défaut | — |
| **P1** | `quasar model assign-rates <m> --policy unit\|sample --seed N` — **livré ✅** | valuation des modèles qualitatifs (T helper / TCR / N2a) |
| **V1** | `quasar verify maboss … --samples N --max-time T --json` — **livré ✅** (quantiles + nodeActivation) | H2, H4 |
| **V2** | `quasar verify storm … --metric prob\|expected-time --json` — **livré ✅** | oracle exact scriptable, point d'explosion H5 |
| **A1** | recouvrement H4 fait au niveau notebook via V1 (Jaccard) — **résolu** | H4 (recouvrement) |
| **A2** | `quasar bench sweep [m…] --metric reachability\|fixpoints\|load --json` — **livré ✅** | courbe H5 automatisée |
| **A3** | `quasar bench ablation <m> --goal … --json` (CTMC/MDD/CEGAR) — **livré ✅** | H6 (ablation) |
| **M1** | options globales `--json` / `--cache-dir` (ou `QUASAR_JSON`/`QUASAR_CACHE_DIR`) — **livré ✅** | repro / cache CLI |
| **M2** | `quasar bench validate … --json` → `{soundness, tightness, relGap, delayGap, scenarioOverlap}` — **livré ✅** | rapport H1–H4 consolidé |

## Note d'environnement (rendu des figures)

Sur certaines installations (numpy adossé à **MKL**), `numpy.dot` — utilisé par
les transforms de matplotlib — peut se bloquer à cause d'un deadlock du runtime
de threads MKL. Le notebook **corrige** ce point en positionnant
`MKL_THREADING_LAYER=SEQUENTIAL` **avant** l'import de numpy (Section 0). Les 4
figures sont donc rendues normalement (PNG dans `figures/` + affichage inline).

Par sécurité, le rendu reste exécuté dans un **processus isolé avec timeout** :
si matplotlib échouait malgré tout, la cellule afficherait et exporterait les
**données** (`figN_data.csv`) sans bloquer ni fabriquer d'image.

## Acquisition des modèles externes

```bash
bash experiments/acquire_models.sh   # GINsim .zginml -> SBML-qual dans external/
```

Le script télécharge **TCR (40)**, **Th Naldi (65)** et **Th Abou-Jaoudé (101)**
depuis GINsim et les convertit en SBML-qual (via GINsim, seul à lire le ginml ;
bioLQM 0.8 ne sait que l'exporter). La Section 1 du notebook les importe, la
Section 2 les **value** (`model assign-rates --policy unit`, fiche P1), et la
Section 6 (H5/H5b) les utilise pour la scalabilité. Voir `external/PROVENANCE.md`
pour les citations et la licence.

Les fichiers `external/*.sbml`/`*.anx` ne sont **pas versionnés** (artefacts
tiers régénérables). Le modèle **N2a** (Vasaikar 2015) est absent de GINsim — à
reconstruire manuellement, **non fabriqué** ici.
