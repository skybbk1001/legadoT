package io.legado.shared

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

expect fun platformName(): String

@Composable
fun SharedStub() {
    Text(text = "Shared on ${platformName()}")
}
