# Prise en main

## Prérequis

- **JDK 21** (Eclipse Adoptium recommandé).
- **sbt** 1.9+.
- *(optionnel)* binaires **NuSMV**, **storm**, **MaBoSS** dans le `PATH` pour le
  groupe `verify` — détectés à l'exécution, échec propre si absents.
- *(optionnel)* **Python 3.9+** + `jpype1` pour les bindings `pyquasar`.

## Build

```bash
sbt compile          # compile tous les modules
sbt test             # tous les tests (dont property tests de justesse)
sbt "cli/assembly"   # fat-jar -> cli/target/scala-3.3.4/quasar.jar
```

Pour un binaire natif à démarrage rapide (GraalVM `native-image`) :

```bash
sbt "project cli" nativeImage
```

## Premier modèle

Le dépôt embarque des modèles de référence dans `bench/models/`
(p53-mdm2, cellfate, démos phase-type et multivalué).

```bash
JAR=cli/target/scala-3.3.4/quasar.jar

# 1. inspecter un modèle MaBoSS
java -jar $JAR model info    bench/models/p53-mdm2.bnd
java -jar $JAR model inspect bench/models/p53-mdm2.bnd

# 2. le convertir vers l'IR ANX canonique
java -jar $JAR model convert bench/models/p53-mdm2.bnd p53.anx

# 3. atteignabilité qualitative
java -jar $JAR analyze reachability p53.anx --goal p53=1 --mode both

# 4. probabilité exacte (CTMC)
java -jar $JAR analyze probability  p53.anx --goal p53=1

# 5. confronter à l'oracle exact (cône)
java -jar $JAR analyze compare      p53.anx --goal p53=1
```

Toutes les commandes acceptent `--json` pour une sortie machine stable.

## En dev, sans packager

```bash
sbt "project cli" "run model info bench/models/p53-mdm2.bnd"
```

## Étape suivante

La référence complète des commandes est dans le **[Guide CLI](usage.md)**.
Pour piloter QUASAR depuis un notebook, voir **[Bindings Python](python.md)**.
