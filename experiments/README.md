# Validation du chapitre 4 — notebook expérimental

Ce dossier contient l'expérimentation qui **pilote QUASAR** pour valider
empiriquement les contributions du chapitre 4 (justesse, finesse, scénarios,
scalabilité). Brief complet : `../PLAN_EXPERIMENTAL_validation_chap4.md`.

## Fichiers

| Fichier | Rôle |
|---|---|
| `validation_chap4.ipynb` | notebook reproductible (exécuté de bout en bout) |
| `_build_notebook.py` | **générateur** du notebook (source de vérité ; régénère le `.ipynb`) |
| `figures/` | PNG des figures + données `figN_data.csv` |
| `validation_chap4.bundle` | run-bundle reproductible (modèle + tags) |

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
| **H5 — scalabilité** | ✅ AUTOMATISÉ (fiche A2, `bench sweep`) — courbe taille→temps ; Storm exact (V2) en regard ; grands modèles dès qu'acquis |
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

Les modèles qualitatifs du plan (T helper Naldi, Abou-Jaoudé 101, TCR, apoptose
N2a) ne sont pas dans le dépôt. Pour les inclure : déposer leur SBML-qual dans
`experiments/external/<nom>.sbml` puis ré-exécuter (la Section 1 les importe). La
Section 2 les **value automatiquement** via `model assign-rates --policy unit`
(fiche P1, désormais implémentée) ; l'analyse de sensibilité utilise `--policy
sample --seed N`. Reste seulement l'**acquisition** (téléchargement) à la charge
de l'utilisateur.
