# BonCommandeChecker — Android

Application Android native en Kotlin / Jetpack Compose pour contrôler les prix des bons de commande par rapport à une mercuriale modifiable.

## Fonctionnalités

- Mercuriale locale : référence, désignation, fournisseur, unité, prix de référence.
- Ajout, modification et suppression manuels.
- Import de mercuriale en **CSV** ou **Excel `.xlsx`**.
- Colonnes reconnues : Référence/Ref/Code, Désignation/Libellé/Nom, Fournisseur, Unité, Prix/PU/Tarif/Prix HT.
- Les références déjà existantes sont mises à jour à l'import.
- Contrôle d'un bon avec tolérance en pourcentage.
- Import d'un **PDF** ou d'une **photo** du bon de commande.
- Prise de photo directement avec l'appareil photo.
- OCR ML Kit sur l'appareil, puis rapprochement avec les références de la mercuriale.
- Possibilité de corriger manuellement chaque ligne détectée.
- Totaux commande / référence et détection des prix trop élevés.

## Important sur la lecture automatique

La reconnaissance d'un bon dépend de sa mise en page et de la qualité de la photo/PDF. Après import, il faut vérifier les quantités et prix détectés. Les références présentes dans la mercuriale donnent les meilleurs résultats.

Pour les PDF, les 12 premières pages sont analysées afin de limiter la mémoire utilisée sur le téléphone.

## Ouvrir le projet

1. Ouvrir le dossier dans Android Studio.
2. Laisser Gradle synchroniser les dépendances.
3. Lancer sur un téléphone Android 8.0 (API 26) ou supérieur.

Le module OCR utilise Google ML Kit (`play-services-mlkit-text-recognition:19.0.1`). Le modèle OCR peut être téléchargé par Google Play Services lors de la première installation/utilisation.

## Nouveauté 1.2 — création de produits depuis un bon scanné

Après l'analyse OCR d'un bon de commande (photo ou PDF), l'application tente désormais de conserver aussi les lignes qui ne correspondent à aucune référence de la mercuriale. Elles apparaissent comme **Référence inconnue**.

Pour chaque ligne inconnue, le bouton **Ajouter à la mercuriale** ouvre une fiche préremplie avec la référence, la désignation et le prix détectés. L'utilisateur peut corriger ces valeurs, renseigner le fournisseur et l'unité, puis enregistrer. Si la référence existe déjà, la fiche existante est mise à jour au lieu de créer un doublon.

La détection OCR reste heuristique : pour un format de bon fournisseur particulier, la fiabilité peut être encore améliorée en adaptant les règles à partir d'un exemple réel anonymisé.

## Compilation automatique de l'APK avec GitHub Actions

Le projet contient maintenant le workflow `.github/workflows/build-apk.yml`.

### Utilisation
1. Créez un dépôt GitHub vide.
2. Envoyez tout le contenu du dossier `BonCommandeChecker` dans ce dépôt.
3. Ouvrez l'onglet **Actions** du dépôt GitHub.
4. Sélectionnez **Build Android APK**.
5. Cliquez sur **Run workflow** puis **Run workflow**.
6. Quand la compilation est terminée, ouvrez l'exécution et téléchargez l'artefact **BonCommandeChecker-APK**.
7. Décompressez l'artefact : il contient `BonCommandeChecker.apk`, installable sur Android.

Le workflow se lance aussi automatiquement à chaque `push` sur les branches `main` ou `master`.

### Installation sur Android
Android peut demander l'autorisation **Installer des applications inconnues** pour l'application utilisée afin d'ouvrir l'APK (navigateur ou gestionnaire de fichiers). Activez cette autorisation uniquement pour installer votre propre APK, puis vous pouvez la désactiver ensuite.
