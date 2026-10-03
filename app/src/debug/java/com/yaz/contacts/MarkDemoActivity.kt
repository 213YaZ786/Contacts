package com.yaz.contacts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yaz.contacts.ui.component.LoadingMark
import com.yaz.contacts.ui.theme.AppSurface

/** Debug builds only: the loading mark at the sizes the app draws it, to look at it run. */
class MarkDemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppSurface {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                    LoadingMark(size = 160.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        LoadingMark(size = 96.dp)
                        LoadingMark(size = 72.dp)
                        LoadingMark(size = 48.dp)
                        LoadingMark(size = 28.dp)
                    }
                    LoadingMark(size = 72.dp, running = false, progress = 0.6f)
                }
            }
        }
    }
}
