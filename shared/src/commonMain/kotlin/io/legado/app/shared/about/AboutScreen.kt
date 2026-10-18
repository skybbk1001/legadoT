package io.legado.app.shared.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class AboutEntry(
    val title: String,
    val subtitle: String? = null,
    val icon: ImageVector? = null,
    val action: AboutAction
)

data class AboutUiState(
    val appName: String,
    val appSummary: String = "",
    val appSummaryHighlight: String? = null,
    val versionName: String = "",
    val versionCode: Long = 0L,
    val mainEntries: List<AboutEntry> = emptyList(),
    val otherTitle: String = "",
    val otherEntries: List<AboutEntry> = emptyList()
)

sealed interface AboutAction {
    data object OpenContributors : AboutAction
    data object ShowUpdateLog : AboutAction
    data object CheckUpdate : AboutAction
    data object ShowCrashLogs : AboutAction
    data object SaveLog : AboutAction
    data object CreateHeapDump : AboutAction
    data object ShowPrivacyPolicy : AboutAction
    data object ShowLicense : AboutAction
    data object ShowDisclaimer : AboutAction
}

@Composable
fun AboutScreen(state: AboutUiState, onAction: (AboutAction) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 1.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp)
        ) {
            Column(Modifier.padding(10.dp)) {
                Text(
                    text = state.appName,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                if (state.appSummary.isNotEmpty()) {
                    AboutSummary(
                        summary = state.appSummary,
                        highlight = state.appSummaryHighlight
                    )
                }
            }
        }
        state.mainEntries.forEach { entry ->
            AboutEntryItem(entry, onAction)
        }
        if (state.otherTitle.isNotEmpty() && state.otherEntries.isNotEmpty()) {
            Text(
                text = state.otherTitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            state.otherEntries.forEach { entry ->
                AboutEntryItem(entry, onAction)
            }
        }
    }
}

@Composable
private fun AboutSummary(summary: String, highlight: String?) {
    if (highlight.isNullOrEmpty() || !summary.contains(highlight)) {
        Text(text = summary, style = MaterialTheme.typography.bodyMedium)
        return
    }
    val start = summary.indexOf(highlight)
    Text(
        text = buildAnnotatedString {
            append(summary)
            addStyle(
                SpanStyle(color = MaterialTheme.colorScheme.primary),
                start,
                start + highlight.length
            )
        },
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun AboutEntryItem(entry: AboutEntry, onAction: (AboutAction) -> Unit) {
    ListItem(
        headlineContent = { Text(text = entry.title) },
        supportingContent = entry.subtitle?.let { subtitle ->
            { Text(text = subtitle) }
        },
        leadingContent = entry.icon?.let { icon ->
            {
                Icon(
                    imageVector = icon,
                    contentDescription = null
                )
            }
        },
        modifier = Modifier.clickable { onAction(entry.action) }
    )
}
