// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.gildasledrogoff.faceblur.R
import kotlinx.coroutines.launch

private data class OnboardingPage(
    @StringRes val title: Int,
    @StringRes val body: Int,
    @DrawableRes val icon: Int? = null,
)

private val ONBOARDING_PAGES = listOf(
    OnboardingPage(
        R.string.onboarding_welcome_title,
        R.string.onboarding_welcome_body,
        R.drawable.ic_blur_on
    ),
    OnboardingPage(R.string.onboarding_marker_title, R.string.onboarding_marker_body),
)

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val dimens = dimensFor(currentWidthClass())
    val pagerState = rememberPagerState { ONBOARDING_PAGES.size }
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == ONBOARDING_PAGES.lastIndex
    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }
    CompositionLocalProvider(LocalDimens provides dimens) {
        Scaffold(
            bottomBar = {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(dimens.screenPadding)
                        .padding(bottom = dimens.spacing),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (!isLastPage) {
                            TextButton(onClick = onDone) { Text(stringResource(R.string.onboarding_skip)) }
                        }
                    }
                    PageIndicator(pagerState.currentPage, ONBOARDING_PAGES.size)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        Button(
                            onClick = {
                                if (isLastPage) onDone()
                                else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                            },
                        ) {
                            Text(stringResource(if (isLastPage) R.string.onboarding_start else R.string.onboarding_next))
                        }
                    }
                }
            },
        ) { padding ->
            HorizontalPager(
                pagerState,
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) { index ->
                PageContent(ONBOARDING_PAGES[index])
            }
        }
    }
}

@Composable
private fun PageContent(page: OnboardingPage) {
    val dimens = LocalDimens.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .wrapContentSize()
            .widthIn(max = dimens.contentMaxWidth)
            .padding(dimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dimens.sectionSpacing),
    ) {
        if (page.icon != null) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(112.dp),
            ) {
                Icon(painterResource(page.icon), contentDescription = null, Modifier.padding(28.dp))
            }
        } else {
            MarkerIllustration()
        }
        Text(
            stringResource(page.title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(page.body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MarkerIllustration() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(LocalDimens.current.sectionSpacing),
        modifier = Modifier.semantics(mergeDescendants = true) {},
    ) {
        PhotoThumbnail(R.string.onboarding_marker_preview, isFramed = true)
        PhotoThumbnail(R.string.onboarding_marker_saved, isFramed = false)
    }
}

@Composable
private fun PhotoThumbnail(@StringRes label: Int, isFramed: Boolean) {
    val dimens = LocalDimens.current
    val haloColor = MaterialTheme.colorScheme.scrim
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dimens.spacing),
    ) {
        Box(
            Modifier
                .size(112.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .drawWithContent {
                    drawContent()
                    if (!isFramed) return@drawWithContent
                    val strokeWidth = dimens.overlayStroke.toPx()
                    val dashEffect =
                        PathEffect.dashPathEffect(floatArrayOf(strokeWidth * 4, strokeWidth * 3))
                    val inset = size.minDimension * 0.2f
                    val topLeft = Offset(inset, inset)
                    val frameSize = Size(size.width - 2 * inset, size.height - 2 * inset)
                    drawRect(
                        haloColor.copy(alpha = 0.5f),
                        topLeft,
                        frameSize,
                        style = Stroke(strokeWidth, pathEffect = dashEffect)
                    )
                    drawRect(
                        FACE_MARKER_COLOR,
                        topLeft,
                        frameSize,
                        style = Stroke(strokeWidth, pathEffect = dashEffect)
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_person),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(56.dp)
                    .blur(6.dp),
            )
        }
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PageIndicator(currentPage: Int, pageCount: Int) {
    val description = stringResource(R.string.onboarding_page, currentPage + 1, pageCount)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        repeat(pageCount) { index ->
            val color by animateColorAsState(
                if (index == currentPage) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                label = "dot",
            )
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}
