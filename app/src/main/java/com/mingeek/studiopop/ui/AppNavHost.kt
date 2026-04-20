package com.mingeek.studiopop.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mingeek.studiopop.ui.caption.CaptionScreen
import com.mingeek.studiopop.ui.editor.EditorScreen
import com.mingeek.studiopop.ui.home.HomeScreen
import com.mingeek.studiopop.ui.project.ProjectDetailScreen
import com.mingeek.studiopop.ui.project.ProjectListScreen
import com.mingeek.studiopop.ui.shorts.ShortsScreen
import com.mingeek.studiopop.ui.thumbnail.ThumbnailScreen
import com.mingeek.studiopop.ui.upload.UploadScreen

object Routes {
    const val HOME = "home"
    const val UPLOAD = "upload"
    const val CAPTION = "caption"
    const val EDITOR = "editor"
    const val THUMBNAIL = "thumbnail"
    const val SHORTS = "shorts"
    const val PROJECT_LIST = "projects"
    const val PROJECT_DETAIL = "project/{projectId}"

    // projectId 를 옵셔널 쿼리 파라미터로 넣는 템플릿
    fun routeWithProject(base: String) = "$base?projectId={projectId}"
    fun linkWithProject(base: String, projectId: Long) = "$base?projectId=$projectId"
}

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun AppNavHost() {
    val nav = rememberNavController()

    val projectIdArg = listOf(
        navArgument("projectId") {
            type = NavType.LongType
            defaultValue = 0L
        }
    )

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onNavigateUpload = { nav.navigate(Routes.UPLOAD) },
                onNavigateCaption = { nav.navigate(Routes.CAPTION) },
                onNavigateEditor = { nav.navigate(Routes.EDITOR) },
                onNavigateThumbnail = { nav.navigate(Routes.THUMBNAIL) },
                onNavigateShorts = { nav.navigate(Routes.SHORTS) },
                onNavigateProjects = { nav.navigate(Routes.PROJECT_LIST) },
            )
        }
        composable(Routes.PROJECT_LIST) {
            ProjectListScreen(
                onNavigateBack = { nav.popBackStack() },
                onOpenProject = { id ->
                    nav.navigate(Routes.PROJECT_DETAIL.replace("{projectId}", id.toString()))
                },
            )
        }
        composable(
            route = Routes.PROJECT_DETAIL,
            arguments = listOf(navArgument("projectId") { type = NavType.LongType })
        ) {
            ProjectDetailScreen(
                onNavigateBack = { nav.popBackStack() },
                onNavigateCaption = { id ->
                    nav.navigate(Routes.linkWithProject(Routes.CAPTION, id))
                },
                onNavigateEditor = { id ->
                    nav.navigate(Routes.linkWithProject(Routes.EDITOR, id))
                },
                onNavigateShorts = { id ->
                    nav.navigate(Routes.linkWithProject(Routes.SHORTS, id))
                },
                onNavigateThumbnail = { id ->
                    nav.navigate(Routes.linkWithProject(Routes.THUMBNAIL, id))
                },
                onNavigateUpload = { id ->
                    nav.navigate(Routes.linkWithProject(Routes.UPLOAD, id))
                },
            )
        }
        composable(
            route = Routes.routeWithProject(Routes.UPLOAD),
            arguments = projectIdArg,
        ) { backStack ->
            val pid = backStack.arguments?.getLong("projectId")?.takeIf { it > 0 }
            UploadScreen(onNavigateBack = { nav.popBackStack() }, projectId = pid)
        }
        composable(Routes.UPLOAD) {
            UploadScreen(onNavigateBack = { nav.popBackStack() })
        }
        composable(
            route = Routes.routeWithProject(Routes.CAPTION),
            arguments = projectIdArg,
        ) { backStack ->
            val pid = backStack.arguments?.getLong("projectId")?.takeIf { it > 0 }
            CaptionScreen(onNavigateBack = { nav.popBackStack() }, projectId = pid)
        }
        composable(Routes.CAPTION) {
            CaptionScreen(onNavigateBack = { nav.popBackStack() })
        }
        composable(
            route = Routes.routeWithProject(Routes.EDITOR),
            arguments = projectIdArg,
        ) { backStack ->
            val pid = backStack.arguments?.getLong("projectId")?.takeIf { it > 0 }
            EditorScreen(onNavigateBack = { nav.popBackStack() }, projectId = pid)
        }
        composable(Routes.EDITOR) {
            EditorScreen(onNavigateBack = { nav.popBackStack() })
        }
        composable(
            route = Routes.routeWithProject(Routes.THUMBNAIL),
            arguments = projectIdArg,
        ) { backStack ->
            val pid = backStack.arguments?.getLong("projectId")?.takeIf { it > 0 }
            ThumbnailScreen(onNavigateBack = { nav.popBackStack() }, projectId = pid)
        }
        composable(Routes.THUMBNAIL) {
            ThumbnailScreen(onNavigateBack = { nav.popBackStack() })
        }
        composable(
            route = Routes.routeWithProject(Routes.SHORTS),
            arguments = projectIdArg,
        ) { backStack ->
            val pid = backStack.arguments?.getLong("projectId")?.takeIf { it > 0 }
            ShortsScreen(onNavigateBack = { nav.popBackStack() }, projectId = pid)
        }
        composable(Routes.SHORTS) {
            ShortsScreen(onNavigateBack = { nav.popBackStack() })
        }
    }
}
