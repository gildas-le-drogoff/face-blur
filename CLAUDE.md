# Claude Development Guidelines

Un autre agent tourne peut-être , si tu vois des modifications qui ne sont pas les tienes, continue, ne t'arrête pas, ne revert rien

No JDK in this environment -> Review carefully instead.

## 🔨 Build Instructions

Il sera peut-être impossible de build, en fonction de l'environnement, si
c'est le cas, il faut alors simplement review avec attention le code et ne pas
essayer de build.

## 🏗️ Architecture Principles

### Clean Architecture Layers

Dependency rule: each layer knows only itself and the layer directly below. The domain depends on no Android type, no DTO, no framework.

## 📱 UI/UX Guidelines

1. Material 3.
2. Explicit error (`UiError`) and loading states.
3. `AnimatedVisibility` for transitions.
4. Handle configuration changes.
5. Accessibility support.

## 🧭 Navigation

Typed routes (no concatenated strings). One destination = one type. `hiltViewModel()` per destination in the `NavHost`.

# RÈGLE D'OR (LA PLUS IMPORTANTE)

À CHAQUE interaction (réponse à une question, génération de code, rédaction, ou simple conversation), tu DOIS écrire TODO.md (ou le mettre à jour):

1. Vérifier l'état du Todo List actuel (que tu conserves en mémoire pendant la session).
2. Proposer une mise à jour de celui-ci si l'action en cours impacte une tâche existante (ex: avancement, blocage, annulation).
3. Ajouter systématiquement une nouvelle tâche si une nouvelle action émerge de notre conversation.

# FORMAT OBLIGATOIRE DU TODO.md

Tu dois structurer le Todo List sous ce format markdown à chaque mise à jour, en le plaçant en bas de ta réponse ou en encadré :

---

📋 **ETAT DU TODO.md** _(Mis à jour le [Heure actuelle])_

**A FAIRE (Priorité Haute)**

- [ ] [Tâche 1] - Échéance : [Date] / Contexte : [Résumé]

**A FAIRE (Priorité Moyenne/Basse)**

- [ ] [Tâche 2] - Échéance : [Date]

**EN COURS**

- [ ] [Tâche 3] - Progression : [X%] / Prochaine action : [détail]

**TERMINÉ (Dernières 24h)**

- [x] [Tâche 4] - Fait le [Date]

---

# EXIGENCES DE MISE À JOUR

- Ne supprime jamais une tâche sans me demander confirmation (sauf si je te dis explicitement de l'annuler).
- À la fin de chaque réponse, si tu as ajouté ou modifié une tâche, indique le par une phrase comme : _"J'ai mis à jour ton Todo List en intégrant [cette action]."_

# ICONES

je ne veux SURTOUT pas de compose-material-icons-extended, il faut utiliser add-material-symbols

add-material-symbols --help

COMMAND: add-material-symbols
DESCRIPTION: Downloads Material Symbols icons from Google Fonts as Android XML vector drawables
SYNTAX: add-material-symbols [OPTIONS] ICON_NAMES_OR_URLS...

OPTIONS:
-o, --output DIR Output directory (default: ./app/src/main/res/drawable)
-s, --style STYLE Icon style: outlined, rounded, sharp (default: outlined)
-f, --fill Use filled variant (default: not filled)
-w, --weight WGHT Weight: 100-700 (default: 400)
-g, --grade GRAD Grade: -25, 0, 200 (default: 0)
--opsz SIZE Optical size: 20, 24, 40, 48 (default: 24)
--svg Also download the SVG into ./output
-h, --help Show this help message

REQUIREMENTS:

- At least one icon name or URL must be provided
- Exit code 0: success or help displayed
- Exit code 1: error or missing inputs

EXAMPLES:

1. add-material-symbols search star home
2. add-material-symbols --style rounded --fill search
3. add-material-symbols -s sharp -w 300 star
4. add-material-symbols --svg search star
5. add-material-symbols "https://fonts.google.com/icons?selected=Material+Symbols+Outlined:search:FILL@0;wght@400;GRAD@0;opsz@24"

REQUIREMENTS:

- At least one icon name or URL must be provided
- Exit code 0: success or help displayed
- Exit code 1: error or missing inputs

EXAMPLES:

1. add-material-symbols search star home
2. add-material-symbols --style rounded --fill search
3. add-material-symbols -s sharp -w 300 star
4. add-material-symbols "https://fonts.google.com/icons?selected=Material+Symbols+Outlined:search:FILL@0;wght@400;GRAD@0;opsz@24"

# Contraintes Responsive Compose

## Invariant global

Tout composable UI DOIT s'adapter à `WindowSizeClass`. Une dimension fixe non justifiée est un bug, pas un détail de style.

## Dimensions (bloquant)

- Interdit : `.px`. Toujours `.dp` / `.sp`.
- Interdit : `Modifier.size/width/height(N.dp)` absolu sur un conteneur de layout. Utiliser `fillMaxWidth(fraction)`, `weight()`, `fillMaxSize()`.
- Tailles fixes tolérées uniquement pour : icônes, bordures, rayons, épaisseurs. Jamais pour surfaces de contenu.
- `GridCells.Fixed(N)` interdit en dur. Utiliser `GridCells.Adaptive(minSize)` ou `N` dérivé de `widthSizeClass`.

## Texte (bloquant)

- Interdit : `fontSize = N.sp` littéral. Toujours `MaterialTheme.typography.*`.
- Couleurs via `MaterialTheme.colorScheme.*`, jamais hex en dur.

## Espacement

- Padding/spacing dérivés d'un barème lié à `widthSizeClass`, pas de constante isolée répétée.
- Centraliser les valeurs dans un objet `Dimens`/barème unique. Pas de magic number `.dp` dispersé.
- `Spacer` avec taille fixe interdit dans un flux extensible → `Modifier.weight(1f)`.

1. Identifier le `widthSizeClass` cible et les breakpoints affectés.
2. Exprimer chaque dimension variable comme fonction de `widthSizeClass`.
3. Ne pas coder de valeur fixe sans l'avoir justifiée (catégorie icône/bordure).

##

Utiliser efficacement la classe `FaceDetectionLog` dans le projet :
Il faut en faire un usage important pour debug aisément

---

## UTILISATION DE FaceDetectionLog

### 1. **Contexte**

Tu dois utiliser `FaceDetectionLog` comme **unique point d'entrée** pour tous les logs dans l'application Android. Cette classe garantit :

- Un préfixe commun `FaceDetection.` pour faciliter le filtrage (`adb logcat -d | rg "(FaceDetection)"`)
- Le traçage des performances via `timed()`
- La surveillance de la mémoire via `heap()`

---

### 2. **Règles d'utilisation**

#### **2.1. Logs standards**

```kotlin
// DEBUG - pour le développement (désactivable via verbose=false)
FaceDetectionLog.d("Database") { "Loading ${users.size} users" }

// INFO - pour les étapes clés
FaceDetectionLog.i("Network") { "API call completed successfully" }

// WARNING - pour les problèmes non critiques
FaceDetectionLog.w("Parser") { "Unexpected field format in line $lineNumber" }

// ERROR - pour les erreurs avec exception
FaceDetectionLog.e("Database", exception) { "Failed to insert record: ${exception.message}" }
```

#### **2.2. Mesure des performances avec `timed()`**

Utilise `timed()` pour **toute opération potentiellement lourde** :

```kotlin
val result = FaceDetectionLog.timed(
    tag = "DataLoader",
    step = "Loading FaceDetection feed from file",
    describe = { trips -> "${trips.size} trips loaded" }
) {
    // Code à mesurer
    loadFaceDetectionFile()
}

// Sans description du résultat
FaceDetectionLog.timed("Parser", "Parsing CSV headers") {
    parseHeaders()
}
```

**Bonnes pratiques :**

- Le `tag` doit identifier le module (ex: "Database", "Network", "Parser")
- Le `step` doit être une phrase lisible (ex: "Loading stops from cache")
- Le `describe` doit résumer le résultat (ex: nombre d'éléments, taille)

#### **2.3. Surveillance mémoire avec `heap()`**

Place `heap()` avant/après des opérations gourmandes :

```kotlin
FaceDetectionLog.heap("Memory", "Before loading large dataset")
val data = loadLargeDataset()
FaceDetectionLog.heap("Memory", "After loading dataset")
```

---

### 3. **Cas d'usage typiques**

#### **3.1. Initialisation de l'application**

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FaceDetectionLog.timed("App", "Application startup") {
            initializeModules()
        }
        FaceDetectionLog.heap("App", "After initialization")
    }
}
```

#### **3.2. Chargement de données**

```kotlin
fun loadFaceDetectionData() {
    FaceDetectionLog.i("FaceDetection") { "Starting data load" }

    val stops = FaceDetectionLog.timed("FaceDetection", "Loading stops") {
        loadStopsFromFile()
    }

    FaceDetectionLog.heap("FaceDetection", "After loading stops")

    val routes = FaceDetectionLog.timed("FaceDetection", "Loading routes") {
        loadRoutesFromFile()
    }

    FaceDetectionLog.i("FaceDetection") { "Data loaded: ${stops.size} stops, ${routes.size} routes" }
}
```

#### **3.3. Gestion des erreurs**

```kotlin
fun processTrip(tripId: String) {
    try {
        FaceDetectionLog.d("TripProcessor") { "Processing trip $tripId" }
        // Traitement...
    } catch (e: IOException) {
        FaceDetectionLog.e("TripProcessor", e) { "Failed to process trip $tripId" }
    }
}
```

---

### 4. **À NE PAS FAIRE**

❌ **Ne pas utiliser `Log.d()` directement** (contourne le préfixe)
❌ **Ne pas utiliser `System.out.println()`** (non traçable)
❌ **Ne pas mettre de logs verbeux en production** (utilise `d()` pas `i()`)

---

### 5. **Paramétrage**

Si les logs deviennent trop verbaux, désactiver les DEBUG :

```kotlin
FaceDetectionLog.verbose = false // Uniquement en release
```

---

### 7. **Rappel pour le debugging**

Pour visualiser tous les logs en une seule commande :

```bash
adb logcat -d | rg "(FaceDetection)"
```

---

# Instructions pour CLAUDE.md concernant Robolectric

```markdown
### Bonnes pratiques

- Spécifier explicitement la version SDK via `@Config(sdk = [...])` pour éviter les
  surprises liées au SDK par défaut.
- Utiliser `ApplicationProvider.getApplicationContext<Context>()` plutôt que de
  mocker le `Context`.
- Pour les coroutines, utiliser `runTest` de `kotlinx-coroutines-test` combiné à
  `ShadowLooper` / `Dispatchers.setMain(...)` si nécessaire.
- Éviter de dupliquer des tests déjà couverts par des tests JUnit purs sans Android.
- Vérifier que la dépendance Robolectric est bien présente avant d'écrire le test ;
  si elle manque, l'ajouter dans le `build.gradle(.kts)` correspondant.
- Garder les tests concis et ciblés (un comportement par test).

**IMPORTANT :** N'utilise **JAMAIS** `android.util.Log` directement. `FaceDetectionLog` est le seul canal de logging autorisé dans ce projet.

Pas de strings hardcodées, toujours traductions en Français/Anglais

Un autre agent tourne peut-être , si tu vois des modifications qui ne sont pas les tienes, continue, ne t'arrête pas, ne revert rien

FAIS DES RÉPONSES TEXTUELLES SIMPLES
if no JDK in this environment -> Review carefully instead.
```
