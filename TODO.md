# TODO.md

📋 **ETAT DU TODO.md** _(Mis à jour le 17/10/2026)_

**A FAIRE (Priorité Haute)**

- [ ] Tester sur appareil (Pixel 8 Pro) les gestes du canevas : toucher pour exclure, glisser pour tracer, pincer pour zoomer - Échéance : 18/10/2026 / Contexte : aucun appareil branché pendant le développement, seuls compilation, lint et tests JVM ont été validés
- [ ] Vérifier sur appareil la conservation des métadonnées en JPEG, PNG et WebP (ExifInterface) et la présence du GPS - Échéance : 18/10/2026 / Contexte : le sélecteur de photos peut retirer la localisation (permission ACCESS_MEDIA_LOCATION non demandée)

**A FAIRE (Priorité Moyenne/Basse)**

- [ ] Rendre la sélection des visages accessible à TalkBack (actions sémantiques par visage) - Échéance : à définir
- [ ] Mesurer le temps de rendu du flou gaussien sur une grande zone manuelle (4096 px) et réduire l'image avant flou si besoin - Échéance : à définir
- [ ] Mettre à jour Gradle 9.8.1 / onnxruntime 1.31.0 (signalés par lint) - Échéance : à définir

**EN COURS**

- (aucune)

**TERMINÉ (Dernières 24h)**

- [x] Choix du type de flou : gaussien (3 flous boîte) ou mosaïque - Fait le 17/10/2026
- [x] Réglage de la taille des carreaux de mosaïque (4 à 25 % du visage) - Fait le 17/10/2026
- [x] Exclusion/inclusion des visages détectés au toucher, boutons « Tout flouter » / « Tout exclure » - Fait le 17/10/2026
- [x] Floutage manuel : mode « Tracer », suppression d'une zone tracée au toucher - Fait le 17/10/2026
- [x] Zoom (pincer, déplacer, bouton d'ajustement, pixels nets au-delà de ×2) - Fait le 17/10/2026
- [x] Métadonnées supprimées par défaut, option pour les conserver (vignette, XMP, MakerNote exclus) - Fait le 17/10/2026
- [x] Export JPEG / PNG / WebP - Fait le 17/10/2026
- [x] Architecture domain / data / ui, ViewModel (état conservé à la rotation), FaceDetectionLog, chaînes FR/EN, mise en page adaptative - Fait le 17/10/2026
- [x] Correction du crash au lancement sous Android 10/11 (couleurs dynamiques réservées à l'API 31+) - Fait le 17/10/2026
- [x] 25 tests JVM (effets de pixels, NMS/sélection, zoom, liste des métadonnées) - Fait le 17/10/2026
