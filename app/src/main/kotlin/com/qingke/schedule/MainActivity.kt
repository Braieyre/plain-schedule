package com.qingke.schedule

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import com.qingke.schedule.data.AppState
import com.qingke.schedule.data.Store
import com.qingke.schedule.model.Course
import com.qingke.schedule.ui.*
import kotlinx.coroutines.launch

/** 导航目的地。刻意用一个 sealed interface 代替 navigation 库，省一个依赖。 */
sealed interface Screen {
    data object Schedule : Screen
    data object Import : Screen
    data object PeriodTimes : Screen
    data object Appearance : Screen
    data class Edit(val course: Course?) : Screen
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val state = AppState(Store(applicationContext))
        setContent {
            CompositionLocalProvider(LocalAppState provides state) {
                AppTheme(state.settings) {
                    AppRoot()
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun AppRoot() {
    var screen by remember { mutableStateOf<Screen>(Screen.Schedule) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    BackHandler(enabled = screen != Screen.Schedule || drawer.isOpen) {
        when {
            drawer.isOpen -> scope.launch { drawer.close() }
            else -> screen = Screen.Schedule
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = screen == Screen.Schedule,
        drawerContent = {
            SettingsDrawer(
                onNavigate = { dest ->
                    scope.launch { drawer.close() }
                    screen = dest
                },
                onClose = { scope.launch { drawer.close() } },
            )
        },
    ) {
        when (val s = screen) {
            Screen.Schedule -> ScheduleScreen(
                onOpenDrawer = { scope.launch { drawer.open() } },
                onAddCourse = { screen = Screen.Edit(null) },
                onEditCourse = { screen = Screen.Edit(it) },
            )
            Screen.Import -> ImportScreen(onBack = { screen = Screen.Schedule })
            Screen.PeriodTimes -> PeriodTimesScreen(onBack = { screen = Screen.Schedule })
            Screen.Appearance -> AppearanceScreen(onBack = { screen = Screen.Schedule })
            is Screen.Edit -> CourseEditScreen(existing = s.course, onDone = { screen = Screen.Schedule })
        }
    }
}
