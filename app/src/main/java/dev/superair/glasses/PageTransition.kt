package dev.superair.glasses

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

/**
 * Turning a Home page, on the glasses and on the cover alike: the old page slides away and fades,
 * the new one slides in from the side it lies on and fades up, as a scroll would carry them.
 */
fun AnimatedContentTransitionScope<Int>.pageTurn(): ContentTransform {
    val forward = targetState > initialState
    val slide = tween<androidx.compose.ui.unit.IntOffset>(PAGE_MILLIS, easing = FastOutSlowInEasing)
    val fade = tween<Float>(PAGE_MILLIS, easing = FastOutSlowInEasing)
    return (slideInHorizontally(slide) { w -> if (forward) w else -w } + fadeIn(fade)) togetherWith
        (slideOutHorizontally(slide) { w -> if (forward) -w else w } + fadeOut(fade)) using
        SizeTransform(clip = false)
}

const val PAGE_MILLIS = 380
