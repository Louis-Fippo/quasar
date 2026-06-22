#!/usr/bin/env bash
# Acquisition reproductible des modèles externes pour l'expérience de scalabilité (H5).
#
# Télécharge les modèles GINsim (.zginml), les convertit en SBML-qual via GINsim
# (mode bioLQM `-lm` — seul GINsim lit nativement le ginml ; bioLQM 0.8 ne sait
# que l'exporter), puis les dépose dans experiments/external/<nom>.sbml.
# Le notebook (Section 1/2) les importe et les value alors automatiquement.
#
# Prérequis : bash, curl, java (JDK 21). Réseau requis (GINsim + GitHub).
# Usage : bash experiments/acquire_models.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
EXT="$HERE/external"
SRC="$EXT/source"
CACHE="${QUASAR_CACHE_HOME:-$HERE/.acquire-cache}"
GINSIM_JAR="$CACHE/GINsim.jar"
GINSIM_URL="https://github.com/GINsim/GINsim/releases/download/latest/GINsim-3.0.0b-SNAPSHOT-jar-with-dependencies.jar"
BASE="http://ginsim.org/models"

# nom -> chemin du .zginml sur ginsim.org
declare -A MODELS=(
  [tcr]="2006-mammal-tcell-activation/TCRsig40.zginml"
  [thelper-naldi]="2010-mammal-th-differentiation/Th_differentiation_full_annotated_model.zginml"
  [thelper-aboujaoude]="2014-mammal-th-differentiation/Frontiers-Th-Full-model-annotated.zginml"
)

mkdir -p "$EXT" "$SRC" "$CACHE"

if [ ! -f "$GINSIM_JAR" ]; then
  echo "→ téléchargement de GINsim (lecteur ginml) …"
  curl -fsSL --max-time 300 "$GINSIM_URL" -o "$GINSIM_JAR"
fi

for name in "${!MODELS[@]}"; do
  zginml="$SRC/$name.zginml"
  sbml="$EXT/$name.sbml"
  echo "→ $name"
  curl -fsSL --max-time 120 "$BASE/${MODELS[$name]}" -o "$zginml"
  java -jar "$GINSIM_JAR" -lm "$zginml" "$sbml"
  echo "   $sbml ($(stat -c%s "$sbml" 2>/dev/null) o)"
done

echo
echo "✅ Modèles déposés dans $EXT/ (SBML-qual). Le notebook les importe/value automatiquement."
echo "ℹ️  n2a-apoptosis (Vasaikar 2015, PMC4548197) n'est pas dans GINsim : à reconstruire"
echo "    manuellement depuis l'article — non acquis ici (aucune fabrication)."
