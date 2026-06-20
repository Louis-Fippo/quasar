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
| **H2 — justesse (délai)** | ⚠️ BLOQUÉ — `verify maboss` n'expose pas la distribution des temps (fiche V1) |
| **H3 — finesse** | ✅ VALIDÉ — écart nul (P(R) calculée exactement sur ces modèles) |
| **H4 — scénarios** | ⚠️ PARTIEL — scénario QUASAR extrait ; recouvrement oracle BLOQUÉ (V1/A1) |
| **H5 — scalabilité** | ⚠️ PARTIEL — modèles disponibles seulement (grands modèles non acquis) |
| **H6 — optimisations** | ⚠️ PARTIEL — convergence anytime VALIDÉE ; ablation semi-anneau/ZDD BLOQUÉE (A2/A3) |

## Modules QUASAR à arbitrer (Section 7 du notebook)

L'expérience révèle des capacités requises mais absentes de la CLI. **Aucune
n'est implémentée dans le notebook** ; elles sont décrites pour décision :

| Id | Proposition | Débloque |
|---|---|---|
| **P0** | `pyquasar` (façade Python) — **déjà livré** ; on garde le pilotage CLI par défaut | — |
| **P1** | `quasar model assign-rates <m> --policy unit\|sample --seed N` | valuation T helper / TCR / N2a |
| **V1** | `quasar verify maboss … --samples N --max-time T --emit hitting-time-distribution --json` | H2, H4 |
| **V2** | `quasar verify storm … --metric prob\|expected-time --json` | oracle exact externe scriptable |
| **A1** | `quasar analyze scenario … --compare-oracle maboss --json` | H4 (recouvrement) |
| **A2** | `quasar bench sweep --models … --metric time --json` | courbe H5 automatisée |
| **A3** | options solveur `--semiring\|--anytime\|--cycle-policy` + `quasar bench ablation` | H6a / H6c |
| **M1** | options globales `--json` et `--cache-dir` | repro / cache CLI |
| **M2** | `quasar bench validate … --json` → `{soundness, tightness, delay_gap, scenario_overlap}` | rapport H1–H4 |

## Note d'environnement

Le rendu **matplotlib** peut être défaillant sur certaines installations locales
(blocage de `Figure.draw` tous backends confondus, indépendant de QUASAR). Les
cellules de figures sont donc rendues dans un **processus isolé avec timeout** :
si le rendu échoue, la cellule affiche et exporte les **données** (`figN_data.csv`)
et le signale, sans bloquer ni fabriquer d'image. Les figures se génèrent
normalement sur l'image CoLoMoTo.

## Acquisition des modèles externes

Les modèles qualitatifs du plan (T helper Naldi, Abou-Jaoudé 101, TCR, apoptose
N2a) ne sont pas dans le dépôt. Pour les inclure : déposer leur SBML-qual dans
`experiments/external/<nom>.sbml` puis ré-exécuter (la Section 1 les importe).
Leur **valuation** reste bloquée tant que la fiche **P1** n'est pas implémentée.
