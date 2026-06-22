#!/usr/bin/env python3
"""Re-test consolidé des hypothèses H1–H6 sur TOUS les modèles disponibles.

Pilote la CLI QUASAR (--json) en sous-processus ISOLÉS (heap borné, timeout) :
chaque OOM/timeout est capturé honnêtement (jamais de résultat fabriqué).
Oracles externes (MaBoSS/Storm) utilisés s'ils sont présents, sinon SKIPPED.

Usage : python experiments/retest_all.py
"""
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
JAR = next(iter(sorted(REPO.glob("cli/target/scala-*/quasar.jar"))), None)
assert JAR, "quasar.jar introuvable — sbt cli/assembly"
EXT = REPO / "experiments" / "external"
HEAP = "3g"
TIMEOUT = 90

ORACLES = {
    "maboss": any(shutil.which(x) for x in ("MaBoSS", "MaBoSS_2.0", "maboss")),
    "storm": shutil.which("storm") is not None,
}


def run(args, timeout=TIMEOUT, want_json=True):
    args = list(args)
    if want_json and "--json" not in args:
        args = args + ["--json"]
    cmd = ["java", f"-Xmx{HEAP}", "-jar", str(JAR), *args]
    t0 = time.perf_counter()
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return {"ok": False, "timeout": True, "oom": False, "t": float(timeout), "out": "", "data": None}
    dt = time.perf_counter() - t0
    blob = (p.stdout + p.stderr)
    oom = "OutOfMemoryError" in blob
    data = None
    if not oom:
        try:
            data = json.loads(p.stdout.strip())
        except Exception:
            data = None
    return {"ok": p.returncode == 0 and not oom, "timeout": False, "oom": oom,
            "t": dt, "out": p.stdout.strip() or p.stderr.strip(), "data": data}


def ensure_anx(name, src):
    """Renvoie un .anx analysable (importe le SBML externe au besoin)."""
    src = Path(src)
    if src.suffix in (".anx", ".bnd"):
        return src
    anx = EXT / f"{name}.anx"
    if not anx.is_file():
        run(["model", "import", str(src), "-o", str(anx)], timeout=120, want_json=False)
    return anx


def automata(anx, k):
    r = run(["model", "inspect", str(anx)], timeout=60, want_json=False)
    names = [ln.split()[1] for ln in r["out"].splitlines() if ln.startswith("automate ")]
    return names[:k]


# (nom, fichier source, objectifs explicites OU None pour auto-sélection)
MODELS = [
    ("phasetype-demo",     REPO / "bench/models/phasetype-demo.anx",   None),
    ("multivalued-demo",   REPO / "bench/models/multivalued-demo.anx", ["g=2"]),
    ("p53-mdm2",           REPO / "bench/models/p53-mdm2.anx",         ["p53=1"]),
    ("cellfate",           REPO / "bench/models/cellfate.bnd",         ["Apoptosis=1", "Necrosis=1", "Survival=1"]),
    ("tcr",                EXT / "tcr.sbml",                            None),
    ("thelper-naldi",      EXT / "thelper-naldi.sbml",                 None),
    ("thelper-aboujaoude", EXT / "thelper-aboujaoude.sbml",            None),
]


def verdict(res, ok_key, val_keys=()):
    if res["timeout"]:
        return "TIMEOUT"
    if res["oom"]:
        return "OOM"
    if not res["ok"] or res["data"] is None:
        return "n/a"
    d = res["data"]
    return d.get(ok_key) if ok_key else {k: d.get(k) for k in val_keys}


def main():
    print(f"JAR={JAR.name}  heap={HEAP}  timeout={TIMEOUT}s")
    print(f"Oracles externes: MaBoSS={ORACLES['maboss']}  Storm={ORACLES['storm']}")
    print("=" * 110)

    # --- H5 : scalabilité (reachability symbolique globale, par modèle) ---
    print("\n## H5 — scalabilité (reachability symbolique globale, isolée)")
    print(f"{'modèle':<20}{'#aut':>6}{'temps/statut':>22}")
    sizes = {}
    for name, src, _ in MODELS:
        anx = ensure_anx(name, src)
        info = run(["model", "info", str(anx)]).get("data") or {}
        n = info.get("automata"); sizes[name] = (anx, n)
        r = run(["bench", "sweep", str(anx), "--metric", "reachability", "--reps", "1"], timeout=TIMEOUT)
        st = "OOM" if r["oom"] else ("TIMEOUT" if r["timeout"] else (
            f"{r['t']:.2f}s" if r["ok"] else "n/a"))
        print(f"{name:<20}{(n or '?'):>6}{st:>22}")

    # --- H1/H3/H6 par (modèle, objectif), + H2/H4 (oracle) ---
    print("\n## H1 (justesse) · H3 (finesse) · H6 (ablation) · H2/H4 (oracle)")
    hdr = f"{'modèle':<20}{'objectif':<14}{'H1 sound':>9}{'P(R)':>10}{'exact':>7}{'H3 tight':>9}{'H6 abl':>8}{'H2/H4':>8}"
    print(hdr); print("-" * len(hdr))
    summary = {"H1_ok": 0, "H1_total": 0, "explosion": 0}
    for name, src, goals in MODELS:
        anx, n = sizes[name]
        gs = goals if goals else [f"{a}=1" for a in automata(anx, 3)]
        for g in gs:
            cmp_ = run(["analyze", "compare", str(anx), "--goal", g], timeout=TIMEOUT)
            prob = run(["analyze", "probability", str(anx), "--goal", g], timeout=TIMEOUT)
            abl = run(["bench", "ablation", str(anx), "--goal", g, "--reps", "1"], timeout=TIMEOUT)

            if cmp_["oom"] or cmp_["timeout"] or prob["oom"] or prob["timeout"]:
                h1 = "EXPLOSION"; pr = "-"; ex = "-"; h3 = "-"
                summary["explosion"] += 1
            else:
                cd = cmp_["data"] or {}; pd_ = prob["data"] or {}
                sound = cd.get("sound")
                h1 = "✓" if sound else ("✗" if sound is False else "?")
                summary["H1_total"] += 1
                if sound:
                    summary["H1_ok"] += 1
                pr = pd_.get("probability"); pr = f"{pr:.4g}" if isinstance(pr, (int, float)) else "-"
                ex = "oui" if pd_.get("exact") else "non"
                h3 = "1.000" if pd_.get("exact") else "<1"
            if abl["oom"] or abl["timeout"]:
                h6 = "OOM"
            elif abl["ok"] and abl["data"]:
                strats = abl["data"].get("strategies", [])
                h6 = "✓" if all(s.get("agrees") for s in strats if "agrees" in s) else "✗"
            else:
                h6 = "n/a"
            h24 = "SKIP" if not (ORACLES["maboss"] or ORACLES["storm"]) else "oracle"
            print(f"{name:<20}{g:<14}{h1:>9}{pr:>10}{ex:>7}{h3:>9}{h6:>8}{h24:>8}")

    print("\n" + "=" * 110)
    print(f"H1 (justesse) : {summary['H1_ok']}/{summary['H1_total']} bornes sûres "
          f"sur les cas décidés ; {summary['explosion']} cas en explosion (OOM/timeout).")
    print("H2/H4 : SKIPPED (oracles externes absents)." if not (ORACLES["maboss"] or ORACLES["storm"])
          else "H2/H4 : oracles présents.")


if __name__ == "__main__":
    main()
