# Modèles externes — provenance

Modèles acquis pour le volet **scalabilité (H5)**. Ils ne sont **pas versionnés**
dans le dépôt (artefacts tiers) : les régénérer avec `bash experiments/acquire_models.sh`
(télécharge le `.zginml` depuis GINsim et le convertit en SBML-qual via GINsim).

| Fichier (`external/`) | Modèle | Taille | Source |
|---|---|---|---|
| `tcr.sbml` | Signalisation TCR (TCRsig40) — **40 automates** | ~40 nœuds | GINsim `2006-mammal-tcell-activation` ; Saez-Rodriguez et al. 2007, *PLoS Comput Biol* |
| `thelper-naldi.sbml` | Différenciation Th (modèle complet annoté) — **65 automates** | ~65 nœuds | GINsim `2010-mammal-th-differentiation` ; Naldi et al. 2010, *PLoS Comput Biol* 6(9):e1000912 |
| `thelper-aboujaoude.sbml` | Différenciation Th étendue — **101 automates** | 101 composants | GINsim `2014-mammal-th-differentiation` ; Abou-Jaoudé et al. 2014, *Front. Bioeng. Biotechnol.* |

Conversion : `.zginml` (format natif GINsim) → SBML-qual via `GINsim -lm`
(bioLQM 0.8 sait *exporter* le ginml mais pas le *charger* : `canLoad=false`).
QUASAR importe ensuite le SBML-qual via bioLQM, puis le notebook value les taux
par `model assign-rates --policy unit` (fiche P1).

## Non acquis

- **n2a-apoptosis** (apoptose neuronale N2a/HSP70, Vasaikar et al. 2015,
  PMC4548197) : **absent de GINsim**, à reconstruire manuellement depuis les
  données supplémentaires de l'article. Non fabriqué ici — reste marqué bloqué
  dans le notebook tant que le modèle n'a pas été reconstruit et déposé.

Les `.zginml` originaux sont conservés sous `external/source/` (également non
versionnés). Citez les articles d'origine ci-dessus pour tout usage.
