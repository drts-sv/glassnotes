package com.glassnotes.dev

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Кружок контакта (#84): фото из телефонной книги, а если его нет — инициалы на акцентном фоне.
 * Используется и в карточке заметки (правый нижний угол), и в окне «прикрепить контакт?».
 */
@Composable
fun ContactAvatar(
    contact: NoteContact,
    size: Dp,
    accent: Color,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var photo by remember(contact.id) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    LaunchedEffect(contact.id) {
        if (contact.id <= 0L) return@LaunchedEffect
        photo = runCatching { loadPhoto(context, contact.id) }.getOrNull()
    }

    val shapeModifier =
        Modifier
            .size(size)
            .clip(CircleShape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)

    val bitmap = photo
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = contact.name,
            modifier = shapeModifier.border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape),
        )
    } else {
        Box(
            modifier =
                shapeModifier
                    .background(accent.copy(alpha = 0.9f))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = contact.initials,
                color = Color.White,
                fontSize = (size.value * 0.38f).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Маленький «телефонный» значок — чтобы кружок читался как «нажми и позвонишь». */
@Composable
fun DialGlyph(
    size: Dp,
    color: Color,
) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = (w * 0.14f).coerceAtLeast(1.2.dp.toPx())
        // Упрощённая трубка: скруглённый прямоугольник под наклоном.
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.18f, h * 0.2f),
            size = androidx.compose.ui.geometry.Size(w * 0.34f, h * 0.42f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.16f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(w * 0.36f, h * 0.72f),
            end = androidx.compose.ui.geometry.Offset(w * 0.78f, h * 0.86f),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

private fun loadPhoto(
    context: Context,
    contactId: Long,
): androidx.compose.ui.graphics.ImageBitmap? {
    val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, contactId.toString())
    val stream = ContactsContract.Contacts.openContactPhotoInputStream(context.contentResolver, uri, false) ?: return null
    return stream.use { android.graphics.BitmapFactory.decodeStream(it)?.asImageBitmap() }
}
