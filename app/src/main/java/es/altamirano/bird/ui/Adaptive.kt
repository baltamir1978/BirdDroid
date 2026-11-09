package es.altamirano.bird.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Modo escritorio (ventanas que se redimensionan), tableta o móvil tumbado.

/** Ancho al que el contenido deja de estirarse. */
val READABLE_WIDTH = 720.dp

/** Ventana ancha: la navegación pasa de la barra inferior a un riel a la izquierda. */
@Composable
fun isWide(): Boolean = LocalConfiguration.current.screenWidthDp >= 600

/**
 * El margen lateral para que el contenido no pase de [max] y quede centrado, nunca menos de
 * [min]. Va como `contentPadding` de la lista, no limitando la lista: así la rueda del ratón
 * desplaza también sobre los márgenes.
 */
@Composable
fun gutter(min: Dp = 16.dp, max: Dp = READABLE_WIDTH): Dp {
    val w = LocalConfiguration.current.screenWidthDp.dp - if (isWide()) 80.dp else 0.dp // el riel
    return maxOf(min, (w - max) / 2)
}

class NavItem(val selected: Boolean, val onClick: () -> Unit, val icon: @Composable () -> Unit, val label: @Composable () -> Unit)

/** Barra inferior en el móvil; riel a la izquierda si la ventana es ancha. */
@Composable
fun AdaptiveNavigation(items: List<NavItem>, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    if (isWide()) {
        Row(Modifier.fillMaxSize().background(colors.background)) {
            NavigationRail(Modifier.fillMaxHeight(), containerColor = colors.surfaceContainer) {
                Spacer(Modifier.padding(top = 8.dp))
                items.forEach {
                    NavigationRailItem(it.selected, it.onClick, it.icon, label = it.label,
                        colors = NavigationRailItemDefaults.colors(selectedIconColor = colors.primary,
                            selectedTextColor = colors.primary, indicatorColor = colors.primaryContainer))
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.End))) { content() }
        }
    } else {
        Scaffold(containerColor = colors.background, bottomBar = {
            NavigationBar(containerColor = colors.surfaceContainer) {
                items.forEach {
                    NavigationBarItem(it.selected, it.onClick, it.icon, label = it.label,
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = colors.primary,
                            selectedTextColor = colors.primary, indicatorColor = colors.primaryContainer))
                }
            }
        }) { padding ->
            // Arriba, la barra de estado: nada se mete debajo, ni el título ni la lista al desplazarla.
            Box(Modifier.padding(bottom = padding.calculateBottomPadding()).consumeWindowInsets(padding).fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) { content() }
        }
    }
}
