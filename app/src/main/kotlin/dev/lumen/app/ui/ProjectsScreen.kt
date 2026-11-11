package dev.lumen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.app.ProjectRow
import dev.lumen.app.data.ProjectStore

private val Mono = FontFamily.Monospace

/**
 * The project picker: one isolated folder per project, with the active row
 * marked. Every project is a separate root, so the agent in one project cannot
 * read or write another. Deleting a folder is destructive and confirmed; the
 * last project is always kept so the agent has somewhere to work.
 */
@Composable
fun ProjectsScreen(
    colors: LumenColors,
    projects: List<ProjectRow>,
    onSwitch: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenFiles: (() -> Unit)? = null,
    /**
     * Resume a project's newest session. Optional so existing callers keep
     * compiling; when absent the dashboard shows the count without a resume row.
     */
    onOpenSession: ((String) -> Unit)? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
) {
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ProjectRow?>(null) }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.bg)
            .imePadding()
            .padding(bottom = 24.dp),
    ) {
        LumenTopBar(
            colors = colors,
            title = "Projects",
            titleSize = LumenType.title,
            onBack = onBack,
            backTag = "projects-back",
            modifier = Modifier.padding(horizontal = LumenSpacing.page - 4.dp),
            actions = {
                if (onOpenFiles != null) {
                    LumenBarAction(colors, "Files", "projects-files", onOpenFiles)
                }
                LumenBarAction(colors, "New", "projects-new", primary = true, onClick = { creating = true })
            },
        )
        Spacer(Modifier.height(LumenSpacing.md))
        Text(
            "One folder per project. The agent only sees the active folder.",
            color = colors.dim,
            fontFamily = LumenFonts.label,
            fontSize = LumenType.caption,
            lineHeight = LumenType.lineTight,
            modifier = Modifier.padding(horizontal = LumenSpacing.page),
        )
        if (error != null) {
            Spacer(Modifier.height(LumenSpacing.sm))
            Text(
                error,
                color = alertOn(colors),
                fontFamily = LumenFonts.label,
                fontSize = LumenType.caption,
                modifier = Modifier.padding(horizontal = LumenSpacing.page),
            )
        }
        Spacer(Modifier.height(LumenSpacing.lg))

        if (projects.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                StateHint(
                    colors,
                    "No projects yet",
                    detail = "Use New to create the first project folder",
                    tag = "projects-empty",
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("projects-list"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = LumenSpacing.page,
                    end = LumenSpacing.page,
                    top = 2.dp,
                    bottom = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(projects, key = { it.id }) { project ->
                    // The store de-duplicates ids but allows two projects to share
                    // a display name, so flag the collision and let the row show
                    // its folder id — otherwise two "Work" rows are identical.
                    val duplicate = projects.count { it.name.equals(project.name, ignoreCase = true) } > 1
                    ProjectRowCard(
                        colors = colors,
                        project = project,
                        canDelete = projects.size > 1,
                        onSwitch = { onSwitch(project.id) },
                        onDelete = { deleting = project },
                        onOpenSession = onOpenSession,
                        duplicateName = duplicate,
                    )
                }
            }
        }
    }

    if (creating) {
        ProjectNameDialog(
            colors = colors,
            title = "New project",
            confirm = "Create",
            onDismiss = { creating = false },
            onConfirm = { name ->
                onCreate(name)
                // Closes on confirm; a rejected name (duplicate, uncreatable) is
                // reported through the host's `error` slot, which the screen
                // renders above the list with an actionable message.
                creating = false
            },
        )
    }

    deleting?.let { project ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = colors.surface,
            titleContentColor = colors.fg,
            textContentColor = colors.dim,
            title = { Text("Delete project?", fontFamily = LumenFonts.label, fontSize = LumenType.heading, fontWeight = FontWeight.Medium) },
            text = {
                Text(
                    "\"${project.name}\" and every file inside it will be removed. This cannot be undone.",
                    fontFamily = LumenFonts.label,
                    fontSize = LumenType.body,
                    lineHeight = LumenType.lineTight,
                )
            },
            confirmButton = {
                Text(
                    "Delete",
                    color = alertOn(colors),
                    fontFamily = LumenFonts.label,
                    fontSize = LumenType.body,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(LumenShapes.small)
                        .clickable {
                            onDelete(project.id)
                            deleting = null
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("project-delete-confirm"),
                )
            },
            dismissButton = {
                Text(
                    "Cancel",
                    color = colors.dim,
                    fontFamily = LumenFonts.label,
                    fontSize = LumenType.body,
                    modifier = Modifier
                        .clip(LumenShapes.small)
                        .clickable { deleting = null }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("project-delete-cancel"),
                )
            },
        )
    }
}

@Composable
private fun ProjectRowCard(
    colors: LumenColors,
    project: ProjectRow,
    canDelete: Boolean,
    onSwitch: () -> Unit,
    onDelete: () -> Unit,
    onOpenSession: ((String) -> Unit)? = null,
    /** True when another project shares this display name; shows the folder id. */
    duplicateName: Boolean = false,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(LumenShapes.row)
            .background(colors.surface)
            .border(1.dp, if (project.active) colors.water else colors.outline, LumenShapes.row)
            .testTag("project-row-${project.id}"),
    ) {
        // The switch target covers only the title block. Resume and delete are
        // siblings rather than children, so their taps cannot be swallowed by the
        // row's own clickable: a disabled parent clickable takes pointer events
        // before a nested child ever sees them, which is what broke resume.
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !project.active, onClick = onSwitch)
                .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    project.name,
                    color = colors.fg,
                    fontFamily = LumenFonts.label,
                    fontSize = LumenType.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    projectSubtitle(project, duplicateName),
                    color = if (project.active) colors.water else colors.dim,
                    fontFamily = LumenFonts.label,
                    fontSize = LumenType.caption,
                    letterSpacing = 0.3.sp,
                    modifier = if (project.active) Modifier.testTag("project-active") else Modifier,
                )
            }
            if (canDelete) {
                Box(
                    Modifier
                        .sizeIn(minWidth = LumenSize.touchMin, minHeight = LumenSize.touchMin)
                        .clip(LumenShapes.small)
                        .clickable(onClick = onDelete)
                        .semantics { contentDescription = "delete project" }
                        .padding(horizontal = 10.dp)
                        .testTag("project-delete-${project.id}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("\u00d7", color = alertOn(colors), fontFamily = LumenFonts.label, fontSize = 18.sp)
                }
            }
        }
        // Resume sits below the title row, outside its clickable area, so a tap
        // here always opens the session instead of switching projects.
        val last = project.lastSession
        if (last != null && onOpenSession != null) {
            Text(
                text = "resume \u00b7 " + last.title,
                color = colors.dim,
                fontFamily = LumenFonts.label,
                fontSize = LumenType.micro,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // The clickable and the tag sit on the SAME node as the visible
                // text. Nesting a clickable container around a Text made the tap
                // land on the leaf, which carries no action, so the handler never
                // fired. One node, one action.
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = LumenSize.touchMin)
                    .clickable { onOpenSession(last.id) }
                    .padding(start = 14.dp, end = 14.dp)
                    .testTag("project-resume-${project.id}"),
            )
        }
    }
}

/**
 * The secondary line under a project name. Keeps the active hint but adds the
 * session count so a folder with history reads differently from an empty one.
 *
 * [duplicateName] additionally shows the folder id: the store de-duplicates ids
 * but allows two projects to share a display name ("Work" twice), which left the
 * rows indistinguishable with no hint which folder a tap would open. Only shown
 * when disambiguation is actually needed, so the common case stays uncluttered.
 */
private fun projectSubtitle(project: ProjectRow, duplicateName: Boolean = false): String {
    val sessions = when (project.sessionCount) {
        0 -> "no sessions"
        1 -> "1 session"
        else -> "${project.sessionCount} sessions"
    }
    val base = if (project.active) "active \u00b7 agent works here \u00b7 $sessions" else "$sessions \u00b7 tap to switch"
    return if (duplicateName) "$base \u00b7 ${project.id}" else base
}

@Composable
private fun ProjectNameDialog(
    colors: LumenColors,
    title: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    val valid = value.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.fg,
        textContentColor = colors.dim,
        title = { Text(title, fontFamily = LumenFonts.label, fontSize = LumenType.heading, fontWeight = FontWeight.Medium) },
        text = {
            Column {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it.take(ProjectStore.MAX_NAME) },
                    singleLine = true,
                    textStyle = TextStyle(color = colors.fg, fontFamily = LumenFonts.label, fontSize = LumenType.bodyLarge),
                    cursorBrush = SolidColor(colors.water),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(LumenShapes.inset)
                        .background(colors.bg)
                        .border(1.dp, colors.outline, LumenShapes.inset)
                        .padding(10.dp)
                        .testTag("project-name-input"),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "The name becomes a folder; it never touches another project.",
                    color = colors.dim,
                    fontFamily = LumenFonts.label,
                    fontSize = LumenType.micro,
                )
            }
        },
        confirmButton = {
            Text(
                confirm,
                color = if (valid) colors.accent else colors.faint,
                fontFamily = LumenFonts.label,
                fontSize = LumenType.body,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(LumenShapes.small)
                    .clickable(enabled = valid) { onConfirm(value.trim()) }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("project-create-confirm"),
            )
        },
        dismissButton = {
            Text(
                "Cancel",
                color = colors.dim,
                fontFamily = LumenFonts.label,
                fontSize = LumenType.body,
                modifier = Modifier
                    .clip(LumenShapes.small)
                    .clickable { onDismiss() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("project-create-cancel"),
            )
        },
    )
}
