package us.crafties.ffmobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import us.crafties.ffmobile.permissions.StoragePermissionHelper
import java.io.File
import java.text.DecimalFormat

private val MEDIA_EXTENSIONS = setOf(
    "mp4", "mkv", "mov", "avi", "webm", "m4v", "3gp", "ts", "flv", "wmv",
    "mp3", "wav", "flac", "aac", "ogg", "m4a", "opus", "wma", "tta", "wv",
    "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic"
)

@Composable
fun FilePickerDialog(
    title: String = "Choose a file",
    filterMediaOnly: Boolean = true,
    onDismiss: () -> Unit,
    onFileSelected: (File) -> Unit,
) {
    var currentDir by remember { mutableStateOf(StoragePermissionHelper.rootDir()) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f)
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                BreadcrumbRow(currentDir) { currentDir = it }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()

                val entries = remember(currentDir) {
                    (currentDir.listFiles()?.toList() ?: emptyList())
                        .filter { !it.name.startsWith(".") }
                        .filter { it.isDirectory || !filterMediaOnly || it.extension.lowercase() in MEDIA_EXTENSIONS }
                        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                }

                if (entries.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text("Empty folder", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        items(entries) { entry ->
                            FileRow(entry) {
                                if (entry.isDirectory) currentDir = entry
                                else onFileSelected(entry)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun BreadcrumbRow(dir: File, onNavigate: (File) -> Unit) {
    val root = StoragePermissionHelper.rootDir()
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { dir.parentFile?.let(onNavigate) }, enabled = dir.absolutePath != root.absolutePath) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
        }
        Text(
            text = dir.absolutePath.removePrefix(root.parentFile?.absolutePath ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = { onNavigate(root) }) {
            Icon(Icons.Filled.Home, contentDescription = "Storage root")
        }
    }
}

@Composable
private fun FileRow(file: File, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (file.isDirectory) Icons.Filled.Folder else iconForExtension(file.extension),
            contentDescription = null,
            tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            if (!file.isDirectory) {
                Text(formatSize(file.length()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun iconForExtension(ext: String) = when (ext.lowercase()) {
    "mp3", "wav", "flac", "aac", "ogg", "m4a", "opus", "wma" -> Icons.Filled.MusicNote
    "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic" -> Icons.Filled.Image
    else -> Icons.Filled.Movie
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) { value /= 1024; unitIndex++ }
    return "${DecimalFormat("#.##").format(value)} ${units[unitIndex]}"
}
