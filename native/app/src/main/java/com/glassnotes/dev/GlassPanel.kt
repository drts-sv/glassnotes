package com.glassnotes.dev

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/** Ширина стеклянной панели: не на весь экран, но и не узкая карточка. */
private const val PANEL_WIDTH = 0.94f

/** Радиус размытия подложки в dp — то самое «стекло» поверх рабочего стола. */
private const val BLUR_DP = 26

/**
 * Общая стеклянная панель для просмотра, редактирования и ревью (просьба владельца 12.11.2026):
 * одинаковый вид, **адаптивная высота** — панель занимает столько места, сколько нужно содержимому,
 * но не больше [maxHeightFraction] высоты экрана. Содержимое внутри скроллится само.
 *
 * Подложка — приложение (или рабочий стол), размытое системным блюром Android 12+
 * ([WindowManager.LayoutParams.FLAG_BLUR_BEHIND] + `setBackgroundBlurRadius`). На старых версиях
 * остаётся только затемнение — там системного размытия нет.
 */
@Composable
fun GlassPanel(
    accent: Color,
    onDismiss: () -> Unit,
    maxHeightFraction: Float = 0.88f,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        val view = LocalView.current
        // Просим систему размыть всё, что за окном: свой блюр слоя в Compose не размывает
        // содержимое под диалогом, а системный — размывает.
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                val px = (BLUR_DP * view.resources.displayMetrics.density).toInt()
                window.setBackgroundBlurRadius(px)
            }
            onDispose { }
        }

        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.30f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            val limit = maxHeight * maxHeightFraction
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth(PANEL_WIDTH)
                        .wrapContentHeight()
                        .heightIn(max = limit)
                        .clip(RoundedCornerShape(26.dp))
                        .background(Design.glassRgb.copy(alpha = 0.96f))
                        .border(1.dp, Design.border, RoundedCornerShape(26.dp))
                        // Тап внутри панели не должен её закрывать.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {}
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                content = content,
            )
        }
    }
}

/**
 * Широкая кнопка нижней части панели: занимает всю ширину, высота 52 dp — по ней проще попадать.
 * [filled] — акцентная (главное действие), иначе стеклянная с рамкой.
 */
@Composable
fun PanelButton(
    text: String,
    accent: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    androidx.compose.foundation.layout.Box(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (filled) accent.copy(alpha = 0.92f) else Design.glassRgb.copy(alpha = 0.55f))
                .border(
                    1.dp,
                    if (filled) Color.White.copy(alpha = 0.20f) else Design.border,
                    RoundedCornerShape(18.dp),
                )
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = text,
            color = if (filled) Color.White else Design.textPrimary,
            fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp),
        )
    }
}
