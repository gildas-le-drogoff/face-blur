# TODO.md

📋 **ETAT DU TODO.md** _(Mis à jour le 17/10/2026 16:45)_

**A FAIRE (Priorité Haute)**

- [ ] Tester sur appareil (Pixel 8 Pro) les gestes du canevas : toucher une zone pour retirer/remettre le flou, toucher hors zone pour en ajouter une, glisser pour tracer, deux doigts pour zoomer/déplacer - Échéance : 18/10/2026 / Contexte : aucun appareil branché pendant le développement, seuls compilation, lint et tests JVM ont été validés
- [ ] Vérifier sur appareil la conservation des métadonnées en JPEG, PNG et WebP (ExifInterface) et la présence du GPS - Échéance : 18/10/2026 / Contexte : le sélecteur de photos peut retirer la localisation (permission ACCESS_MEDIA_LOCATION non demandée)
- [ ] Ajouter une licence libre au projet (fichier LICENSE) avant la soumission F-Droid - Échéance : à définir / Contexte : aucune licence dans le dépôt, F-Droid l'exige

**A FAIRE (Priorité Moyenne/Basse)**

- [ ] Rendre la sélection des visages accessible à TalkBack (actions sémantiques par visage) - Échéance : à définir
- [ ] Mesurer le temps de rendu du flou gaussien sur une grande zone manuelle (4096 px) et réduire l'image avant flou si besoin - Échéance : à définir
- [ ] Mettre à jour Gradle 9.8.1 (signalé par lint ; onnxruntime n'est plus utilisé) - Échéance : à définir
- [ ] Vérifier sur appareil les 5 langues (fr, de, it, es, en) : bascule de langue système + sélecteur « Langue de l'application » (Android 13+), repli anglais pour une langue non fournie - Échéance : à définir / Contexte : traductions ajoutées sans build possible dans l'environnement

**EN COURS**

- (aucune)

**TERMINÉ (Dernières 24h)**

- [x] Mode unique (sélection et tracé fusionnés) : toucher hors zone = nouvelle zone floutée (taille médiane des visages), zone floutée = retirée, zone exclue = floutée ; slider de seuil inversé (droite = plus de visages) ; export par défaut WebP avec métadonnées conservées ; tests JVM et lint OK - Fait le 17/10/2026
- [x] Modèle unique face_detection_yunet_2026may.onnx (mêmes poids que 2023mar, supprimé) : APK release 1,2 Mo, 29 tests OK, 57/57 visages sur appareil - Fait le 17/10/2026
- [x] Localisation : italien, allemand, espagnol ajoutés (français et anglais existants, anglais = repli via values/), localeFilters et locales_config pour le sélecteur de langue par application - Fait le 17/10/2026
- [x] Remplacement de SCRFD (InsightFace, licence non commerciale) par YuNet (OpenCV Zoo, MIT), exécuté par un moteur ONNX en Kotlin pur (data/onnx) : onnxruntime supprimé - Fait le 17/10/2026
- [x] APK release 16 Mo -> 1,2 Mo (universel, sans .so natif d'inférence, dex compressé, langues FR/EN, bloc de dépendances désactivé pour F-Droid) - Fait le 17/10/2026
- [x] Validation : sorties identiques à onnxruntime (test JVM), mêmes visages que la référence sur 4 photos (57/57 sur une photo de classe), ~155 ms d'inférence sur Pixel 8 Pro en release - Fait le 17/10/2026

- [x] Choix du type de flou : gaussien (3 flous boîte) ou mosaïque - Fait le 17/10/2026
- [x] Réglage de la taille des carreaux de mosaïque (4 à 25 % du visage) - Fait le 17/10/2026
- [x] Exclusion/inclusion des visages détectés au toucher, boutons « Tout flouter » / « Tout exclure » - Fait le 17/10/2026
- [x] Floutage manuel : mode « Tracer », suppression d'une zone tracée au toucher - Fait le 17/10/2026
- [x] Zoom (pincer, déplacer, bouton d'ajustement, pixels nets au-delà de ×2) - Fait le 17/10/2026
- [x] Métadonnées supprimées par défaut, option pour les conserver (vignette, XMP, MakerNote exclus) - Fait le 17/10/2026
- [x] Export JPEG / PNG / WebP - Fait le 17/10/2026
- [x] Architecture domain / data / ui, ViewModel (état conservé à la rotation), FaceDetectionLog, chaînes FR/EN, mise en page adaptative - Fait le 17/10/2026
- [x] Correction du crash au lancement sous Android 10/11 (couleurs dynamiques réservées à l'API 31+) - Fait le 17/10/2026
- [x] 29 tests JVM (effets de pixels, NMS/sélection, zoom, liste des métadonnées, moteur ONNX, décodage YuNet) - Fait le 17/10/2026
