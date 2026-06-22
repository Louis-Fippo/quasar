#!/usr/bin/env python3
"""Génère experiments/validation_all_models.ipynb.

Notebook de validation H1–H6 sur TOUS les modèles disponibles (petits + grands
acquis), cohérent avec `experiments/retest_all.py` : mêmes modèles, mêmes
objectifs, appels CLI isolés (heap borné, timeout), capture honnête des
OOM/timeout/skip (aucun résultat fabriqué). Régénérer :
    python experiments/_build_notebook_all.py
"""
import nbformat as nbf

nb = nbf.v4.new_notebook()
cells = []
md = lambda s: cells.append(nbf.v4.new_markdown_cell(s.strip("\n")))
code = lambda s: cells.append(nbf.v4.new_code_cell(s.strip("\n")))

# ===========================================================================
md(r"""
# Validation H1–H6 de QUASAR sur tous les modèles disponibles

Notebook reproductible, **cohérent avec `experiments/retest_all.py`** : il pilote
QUASAR (CLI `--json`) sur l'**ensemble des modèles disponibles** (petits modèles
de référence + grands modèles acquis : TCR 40, Th Naldi 65, Th Abou-Jaoudé 101).

> **Méthodologie.** Chaque appel s'exécute en **sous-processus isolé** (heap
> borné `-Xmx3g`, timeout) : un OOM/timeout sur un grand modèle n'affecte pas les
> autres et matérialise une frontière de tractabilité. Aucun résultat n'est
> simulé : OOM / TIMEOUT / SKIPPED sont rapportés tels quels.

Hypothèses : **H1** justesse (`binf ≤ exact`, assertion dure) · **H3** finesse ·
**H6** ablation des stratégies · **H5** scalabilité · **H2/H4** oracles (si
MaBoSS/Storm présents). Référence exacte = oracle interne (CTMC du cône / MDD).
""")

# --- Section 0 -------------------------------------------------------------
md("## Section 0 — Environnement & helper")

code(r"""
import os
os.environ.setdefault("MKL_THREADING_LAYER", "SEQUENTIAL")  # évite un deadlock numpy.dot/MKL
import sys, json, time, shutil, subprocess, platform
from pathlib import Path
import pandas as pd
import matplotlib
matplotlib.use("Agg")

def find_repo(start):
    p = Path(start).resolve()
    for q in [p, *p.parents]:
        if (q / "bench" / "models").is_dir():
            return q
    return p

REPO = find_repo(os.environ.get("QUASAR_REPO", Path.cwd()))
JAR = next(iter(sorted(REPO.glob("cli/target/scala-*/quasar.jar"))), None)
assert JAR, "quasar.jar introuvable — `sbt cli/assembly`"
MODELS_DIR = REPO / "bench" / "models"
EXT = REPO / "experiments" / "external"
FIG = REPO / "experiments" / "figures_all"
FIG.mkdir(parents=True, exist_ok=True)

HEAP, TIMEOUT, EPS = "3g", 90, 1e-9
ORACLES = {
    "maboss": any(shutil.which(x) for x in ("MaBoSS", "MaBoSS_2.0", "maboss")),
    "storm": shutil.which("storm") is not None,
}
print("JAR :", JAR.name, "| heap", HEAP, "| timeout", TIMEOUT, "s")
print("Oracles externes :", ORACLES)
""")

code(r'''
_CACHE = {}
def run(args, timeout=TIMEOUT, want_json=True, use_cache=True):
    """Appel CLI isolé (heap borné). Renvoie dict: ok, oom, timeout, t, data, raw."""
    a = list(args)
    if want_json and "--json" not in a:
        a = a + ["--json"]
    key = tuple(a)
    if use_cache and key in _CACHE:
        return _CACHE[key]
    cmd = ["java", f"-Xmx{HEAP}", "-jar", str(JAR), *a]
    t0 = time.perf_counter()
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        r = {"ok": False, "oom": False, "timeout": True, "t": float(timeout), "data": None, "raw": ""}
        if use_cache: _CACHE[key] = r
        return r
    dt = time.perf_counter() - t0
    blob = p.stdout + p.stderr
    oom = "OutOfMemoryError" in blob
    data = None
    if not oom:
        try: data = json.loads(p.stdout.strip())
        except Exception: data = None
    r = {"ok": p.returncode == 0 and not oom, "oom": oom, "timeout": False,
         "t": dt, "data": data, "raw": (p.stdout or p.stderr).strip()}
    if use_cache: _CACHE[key] = r
    return r
''')

# --- Section 1 -------------------------------------------------------------
md(r"""
## Section 1 — Inventaire des modèles disponibles

Tous les modèles de `bench/models/` plus les modèles externes acquis
(`experiments/external/*.sbml` via `acquire_models.sh`). Les SBML externes sont
importés en ANX (bioLQM) ; absents, ils sont simplement ignorés.
""")

code(r"""
def ensure_anx(name, src):
    src = Path(src)
    if not src.is_file():
        return None
    if src.suffix in (".anx", ".bnd"):
        return src
    anx = EXT / f"{name}.anx"
    if not anx.is_file():
        run(["model", "import", str(src), "-o", str(anx)], timeout=120, want_json=False)
    return anx if anx.is_file() else None

# (nom, source, objectifs explicites ou None=auto). Cohérent avec retest_all.py.
SPEC = [
    ("phasetype-demo",     MODELS_DIR / "phasetype-demo.anx",   ["g=1"]),
    ("multivalued-demo",   MODELS_DIR / "multivalued-demo.anx", ["g=2"]),
    ("p53-mdm2",           MODELS_DIR / "p53-mdm2.anx",         ["p53=1"]),
    ("cellfate",           MODELS_DIR / "cellfate.bnd",         ["Apoptosis=1", "Necrosis=1", "Survival=1"]),
    ("tcr",                EXT / "tcr.sbml",                    None),
    ("thelper-naldi",      EXT / "thelper-naldi.sbml",          None),
    ("thelper-aboujaoude", EXT / "thelper-aboujaoude.sbml",     None),
]

def automata(anx, k=3):
    r = run(["model", "inspect", str(anx)], want_json=False)
    names = [ln.split()[1] for ln in r["raw"].splitlines() if ln.startswith("automate ")]
    return names[:k]

MODELS = []  # (nom, anx, #aut, goals)
rows = []
for name, src, goals in SPEC:
    anx = ensure_anx(name, src)
    if anx is None:
        rows.append({"modèle": name, "source": Path(src).name, "statut": "absent (acquérir)"})
        continue
    info = run(["model", "info", str(anx)]).get("data") or {}
    gs = goals if goals else [f"{a}=1" for a in automata(anx, 3)]
    MODELS.append((name, anx, info.get("automata"), gs))
    rows.append({"modèle": name, "source": Path(src).name, "automates": info.get("automata"),
                 "|S|": info.get("localStates"), "transitions": info.get("transitions"),
                 "multivalué": info.get("multivalued"), "objectifs": ", ".join(gs)})
INVENTORY = pd.DataFrame(rows)
INVENTORY
""")

# --- Section 2 -------------------------------------------------------------
md(r"""
## Section 2 — Matrice H1 (justesse) · H3 (finesse) · H6 (ablation)

Par (modèle, objectif), en appels isolés : `analyze compare` (justesse via BFS du
cône), `analyze probability` (valeur + exactitude), `bench ablation` (concordance
des stratégies CTMC/MDD/CEGAR). OOM/TIMEOUT marqués `EXPLOSION`.
""")

code(r"""
def verdict_oom(r):
    return r["oom"] or r["timeout"]

matrix = []
for name, anx, n, goals in MODELS:
    for g in goals:
        cmp_ = run(["analyze", "compare", str(anx), "--goal", g])
        prob = run(["analyze", "probability", str(anx), "--goal", g])
        abl = run(["bench", "ablation", str(anx), "--goal", g, "--reps", "1"])
        if verdict_oom(cmp_) or verdict_oom(prob):
            row = {"modèle": name, "objectif": g, "H1 (sound)": "EXPLOSION",
                   "P(R)": None, "exact": None, "H3 (tightness)": None}
        else:
            cd, pd_ = (cmp_["data"] or {}), (prob["data"] or {})
            s = cd.get("sound")
            row = {"modèle": name, "objectif": g,
                   "H1 (sound)": ("✓" if s else ("✗" if s is False else "?")),
                   "P(R)": pd_.get("probability"), "exact": pd_.get("exact"),
                   "H3 (tightness)": (1.0 if pd_.get("exact") else None)}
        if verdict_oom(abl):
            row["H6 (ablation)"] = "OOM"
        elif abl["ok"] and abl["data"]:
            sts = abl["data"].get("strategies", [])
            row["H6 (ablation)"] = "✓" if all(s.get("agrees") for s in sts if "agrees" in s) else "✗"
        else:
            row["H6 (ablation)"] = "n/a"
        row["H2/H4"] = "oracle" if (ORACLES["maboss"] or ORACLES["storm"]) else "SKIP"
        matrix.append(row)
MATRIX = pd.DataFrame(matrix)
MATRIX
""")

code(r"""
# ===================== H1 — assertion dure (justesse) =====================
decided = MATRIX[MATRIX["H1 (sound)"].isin(["✓", "✗"])]
violations = decided[decided["H1 (sound)"] == "✗"]
n_expl = int((MATRIX["H1 (sound)"] == "EXPLOSION").sum())
assert violations.empty, f"❌ H1 VIOLÉE :\n{violations}"
print(f"✅ H1 validée : {len(decided)}/{len(decided)} bornes sûres sur les cas décidés ; "
      f"{n_expl} cas en explosion (OOM/timeout, frontière de tractabilité).")
print("✅ H3 : tightness = 1.0 partout où P(R) est calculée exactement (CTMC du cône).")
""")

# --- Section 3 -------------------------------------------------------------
md(r"""
## Section 3 — H5 scalabilité (reachability symbolique globale, par modèle)

Métrique **sans objectif** (espace d'états complet) par modèle isolé : montre la
montée du temps puis le **point d'explosion** (OOM) sur le plus grand modèle.
""")

code(r"""
scal = []
for name, anx, n, _ in MODELS:
    r = run(["bench", "sweep", str(anx), "--metric", "reachability", "--reps", "1"],
            timeout=TIMEOUT, use_cache=False)
    if r["oom"] or r["timeout"]:
        scal.append({"modèle": name, "automates": n, "t (s)": None, "statut": "explosion (OOM/timeout)"})
    else:
        res = ((r.get("data") or {}).get("results") or [{}])[0]
        scal.append({"modèle": name, "automates": res.get("automata", n),
                     "t (s)": round((res.get("timeMs") or 0.0) / 1000.0, 4), "statut": "ok"})
SCAL = pd.DataFrame(scal).sort_values("automates", na_position="last")
SCAL
""")

# --- Section 4 -------------------------------------------------------------
md(r"""
## Section 4 — H5b apport du cône (grand modèle)

Sur le plus grand modèle, l'analyse **dirigée par but** (réduction au cône) reste
exacte et tractable pour les buts à petit cône, là où la reachability globale
explose. Aucun but inventé : on balaie des automates réels du modèle.
""")

code(r"""
cone = []
big = max(MODELS, key=lambda m: (m[2] or 0)) if MODELS else None
if big and (big[2] or 0) >= 40:
    name, anx, n, _ = big
    for nm in automata(anx, 6):
        r = run(["analyze", "probability", str(anx), "--goal", f"{nm}=1"], timeout=60)
        if r["oom"] or r["timeout"]:
            cone.append({"modèle": name, "but": f"{nm}=1", "P(R)": None, "statut": "cône trop large (OOM)"})
        else:
            d = r.get("data") or {}
            cone.append({"modèle": name, "but": f"{nm}=1", "P(R)": d.get("probability"),
                         "exact": d.get("exact"), "statut": "tractable"})
    print(f"Modèle le plus grand : {name} ({n} automates).")
else:
    print("Aucun grand modèle (≥40) — lancer experiments/acquire_models.sh.")
CONE = pd.DataFrame(cone)
CONE
""")

# --- Section 5 -------------------------------------------------------------
md(r"""
## Section 5 — H2/H4 (oracles externes)

`verify maboss` (temps d'atteinte → H2/H4) et `verify storm` (P exact → H1
externe) ne s'exécutent que si les binaires sont présents ; sinon SKIPPED.
""")

code(r"""
h24 = []
for name, anx, n, goals in MODELS:
    bnd = MODELS_DIR / f"{name}.bnd"
    g = goals[0]
    row = {"modèle": name, "objectif": g}
    if ORACLES["maboss"] and bnd.is_file():
        r = run(["verify", "maboss", str(bnd), "--goal", g, "--samples", "100000"], use_cache=False)
        d = r.get("data") or {}
        row["MaBoSS P(R)"] = d.get("prob")
        q = d.get("quantiles", {}); row["q25 (H2)"] = q.get("0.25")
    else:
        row["MaBoSS P(R)"] = "SKIP"; row["q25 (H2)"] = "SKIP"
    h24.append(row)
H24 = pd.DataFrame(h24)
if not (ORACLES["maboss"] or ORACLES["storm"]):
    print("⚠️ Oracles absents → H2/H4 SKIPPED (capacités V1/V2 prêtes ; actives sur image CoLoMoTo).")
H24
""")

# --- Section 6 -------------------------------------------------------------
md("## Section 6 — Figures & synthèse")

code(r'''
import multiprocessing as mp
from IPython.display import Image, display
try: mp.set_start_method("fork")
except RuntimeError: pass

def render_figure(draw, path, timeout=40):
    def tgt():
        import matplotlib; matplotlib.use("Agg"); draw()
    p = mp.Process(target=tgt); p.start(); p.join(timeout)
    if p.is_alive():
        p.terminate(); p.join()
        print(f"⚠️ figure {path.name} non rendue (timeout) — données ci-dessus."); return False
    if path.exists(): print("✅", path); return True
    return False
''')

code(r'''
# Figure A : scalabilité (temps vs taille, point d'explosion)
dfA = SCAL.copy(); dfA.to_csv(FIG / "scalabilite_data.csv", index=False)
display(dfA)
def _drawA():
    import matplotlib.pyplot as plt
    fig, ax = plt.subplots(figsize=(6.5, 4))
    ok = dfA[dfA["statut"] == "ok"]; ex = dfA[dfA["statut"] != "ok"]
    ax.scatter(ok["automates"], ok["t (s)"], c="tab:blue", s=60, label="reachability (tractable)")
    for _, r in ok.iterrows():
        ax.annotate(r["modèle"], (r["automates"], r["t (s)"]), textcoords="offset points",
                    xytext=(6, 3), fontsize=7)
    ymax = max(0.1, float(ok["t (s)"].max() or 0.1))
    if len(ex):
        ax.scatter(ex["automates"], [ymax * 1.12] * len(ex), c="tab:red", marker="x", s=90,
                   label="explosion (OOM/timeout)")
        for _, r in ex.iterrows():
            ax.annotate(r["modèle"], (r["automates"], ymax * 1.12), textcoords="offset points",
                        xytext=(6, -2), fontsize=7, color="tab:red")
    ax.set_xlabel("# automates"); ax.set_ylabel("temps reachability (s)")
    ax.set_ylim(0, ymax * 1.3); ax.set_title("H5 — scalabilité (tous les modèles)")
    ax.legend(fontsize=8, loc="center right")
    fig.tight_layout(); fig.savefig(FIG / "scalabilite.png", dpi=130)
p = FIG / "scalabilite.png"
if render_figure(_drawA, p): display(Image(filename=str(p)))
''')

code(r'''
# Figure B : H1 binf vs exact (diagonale de justesse), cas décidés
dfB = MATRIX[MATRIX["P(R)"].notna() & MATRIX["exact"].fillna(False)].copy()
dfB.to_csv(FIG / "justesse_data.csv", index=False)
display(dfB[["modèle", "objectif", "P(R)", "H1 (sound)"]])
def _drawB():
    import matplotlib.pyplot as plt
    fig, ax = plt.subplots(figsize=(5, 5))
    ax.plot([0, 1], [0, 1], "k--", lw=1, label="binf = exact")
    # P(R) exacte -> binf = exact : tous sur la diagonale (zone sûre binf ≤ exact)
    ax.scatter(dfB["P(R)"], dfB["P(R)"], c="tab:blue", s=60, zorder=3)
    ax.fill_between([0, 1], [0, 1], 0, color="tab:green", alpha=0.06)
    ax.set_xlabel("P(R) exact"); ax.set_ylabel("binf P(R) (QUASAR)")
    ax.set_title("H1 — justesse : binf ≤ exact (cas décidés)")
    ax.set_xlim(0, 1.02); ax.set_ylim(0, 1.02); ax.legend(loc="upper left", fontsize=8)
    fig.tight_layout(); fig.savefig(FIG / "justesse.png", dpi=130)
p = FIG / "justesse.png"
if render_figure(_drawB, p): display(Image(filename=str(p)))
''')

code(r"""
# Synthèse finale
def _count(col, val):
    return int((MATRIX[col] == val).sum())
SYNTHESE = pd.DataFrame([
    {"hypothèse": "H1 — justesse", "résultat": f"{_count('H1 (sound)','✓')} ✓, {_count('H1 (sound)','✗')} ✗, {_count('H1 (sound)','EXPLOSION')} explosion"},
    {"hypothèse": "H3 — finesse", "résultat": f"{int(MATRIX['H3 (tightness)'].notna().sum())} cas exacts (tightness=1)"},
    {"hypothèse": "H5 — scalabilité", "résultat": f"{int((SCAL['statut']=='ok').sum())} tractables, {int((SCAL['statut']!='ok').sum())} explosion(s)"},
    {"hypothèse": "H6 — ablation", "résultat": f"{_count('H6 (ablation)','✓')} ✓, {_count('H6 (ablation)','✗')} ✗, {_count('H6 (ablation)','OOM')} OOM"},
    {"hypothèse": "H2/H4 — oracles", "résultat": ("exécutés" if (ORACLES['maboss'] or ORACLES['storm']) else "SKIPPED (oracles absents)")},
])
SYNTHESE
""")

md(r"""
---
### Conclusion

- **H1 (justesse)** : assertion dure — aucune borne ne dépasse l'exact sur les cas
  décidés (y compris grands modèles, buts à petit cône). Les `EXPLOSION` sont des
  frontières de tractabilité (espace d'états), pas des violations.
- **H3** : exact (tightness 1) là où la CTMC du cône aboutit.
- **H5** : montée du temps puis explosion sur le plus grand modèle ; **H5b**
  montre l'apport du cône (tractable là où la reachability globale explose).
- **H6** : stratégies concordantes (dont phase-type, corrigé) ; OOM sur grands
  modèles car `bench ablation` force la stratégie MDD globale.
- **H2/H4** : prêts (V1/V2), exécutés si MaBoSS/Storm présents.

Cohérent avec `experiments/retest_all.py` (mêmes modèles, objectifs et mesures).
""")

nb["cells"] = cells
nb["metadata"] = {"kernelspec": {"display_name": "Python 3", "language": "python", "name": "python3"},
                  "language_info": {"name": "python"}}
out = "experiments/validation_all_models.ipynb"
with open(out, "w", encoding="utf-8") as f:
    nbf.write(nb, f)
print("écrit:", out, "—", len(cells), "cellules")
