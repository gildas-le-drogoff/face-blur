package org.example.faceblur.ui

import androidx.compose.runtime.saveable.Saver

/** Destinations de l'application : une destination = un type (pas de route chaînée). */
sealed interface Destination {
    data object Editor : Destination
    data object Credits : Destination
}

/** Sauvegarde la destination lors des changements de configuration (rotation, process death). */
val DestinationSaver: Saver<Destination, Boolean> = Saver(
    save = { it == Destination.Credits },
    restore = { if (it) Destination.Credits else Destination.Editor },
)
