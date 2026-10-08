package dev.lumen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.app.data.KeyStore
import dev.lumen.app.platform.GitActivity
import dev.lumen.app.platform.GitCommit
import dev.lumen.app.platform.GitHubClient
import dev.lumen.app.platform.GitHubUserStatus
import dev.lumen.app.platform.GitProbe
import dev.lumen.app.platform.GitResult
import dev.lumen.app.platform.GitRunner
import kotlinx.coroutines.launch

private val Mono = FontFamily.Monospace

private const val GITHUB_URL_HINT = "owner/name"

/** Prefilled commit summary; the user can edit it in the confirm dialog. */
private const val DEFAULT_COMMIT_MESSAGE = "lumen: update from agent"

/**
 * The GitHub integration screen. Self-contained: it reads and writes its own
 * [KeyStore] and builds its own [GitHubClient]/[GitRunner] from the local
 * context, so only the optional read-only activity view ([activity],
 * [activityRefreshing], [onRefreshActivity], [loadDiff]) is supplied by the
 * caller; everything else is local.
 *
 * The token field is non-destructive (it is prefilled from the store), the
 * token is only ever sent as an Authorization header / process env and is never
 * placed in a git remote URL or log line.
 */
@Composable
fun GitHubScreen(
    colors: LumenColors,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    activity: GitActivity? = null,
    activityRefreshing: Boolean = false,
    onRefreshActivity: (() -> Unit)? = null,
    loadDiff: (suspend (String) -> String)? = null,
    /** Invoked with the confirmed commit message; the host wires this to the ViewModel. */
    onCommit: (String) -> Unit = {},
    /** Invoked when the user confirms a non-force push; the host wires this to the ViewModel. */
    onPush: () -> Unit = {},
) {
    val context = LocalContext.current
    val keys = remember(context) { KeyStore(context) }
    val client = remember(context) { GitHubClient() }
    val git = remember(context) { GitRunner(context) }
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    var token by rememberSaveable { mutableStateOf(keys.githubToken.orEmpty()) }
    var visible by rememberSaveable { mutableStateOf(false) }
    var repo by rememberSaveable { mutableStateOf(keys.githubRepo) }
    var login by rememberSaveable { mutableStateOf(keys.githubLogin) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var gitProbe by remember { mutableStateOf<GitProbe?>(null) }
    var showCommit by remember { mutableStateOf(false) }
    var showPush by remember { mutableStateOf(false) }

    val connected = token.isNotBlank() || login.isNotBlank()

    fun report(message: String, isError: Boolean) {
        status = message
        statusIsError = isError
    }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.bg)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(start = LumenSpacing.page, end = LumenSpacing.page, top = LumenSpacing.md, bottom = 28.dp),
    ) {
        LumenTopBar(
            colors = colors,
            title = "GitHub",
            titleSize = LumenType.title,
            onBack = onBack,
            backTag = "github-back",
        )
        Spacer(Modifier.height(LumenSpacing.xl))

        if (!connected) {
            StateHint(
                colors = colors,
                text = "GitHub not connected",
                detail = "Add a personal access token to clone and inspect repos",
                tag = "github-disconnected",
            )
            Spacer(Modifier.height(LumenSpacing.xl))
        }

        SectionLabel(colors, "personal access token")
        Spacer(Modifier.height(LumenSpacing.md))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(LumenShapes.panel)
                .background(colors.surface)
                .border(1.dp, colors.outline, LumenShapes.panel)
                .padding(start = LumenSpacing.md, end = LumenSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = token,
                onValueChange = { token = it },
                singleLine = true,
                enabled = !busy,
                textStyle = LocalTextStyle.current.copy(
                    color = colors.fg,
                    fontFamily = Mono,
                    fontSize = LumenType.bodyLarge,
                ),
                cursorBrush = SolidColor(colors.accent),
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrect = false,
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 14.dp)
                    .testTag("github-token"),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        if (token.isEmpty()) {
                            Text(
                                "ghp_\u2026",
                                color = colors.faint,
                                fontFamily = Mono,
                                fontSize = LumenType.bodyLarge,
                            )
                        }
                        inner()
                    }
                },
            )
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = LumenSize.touchMin, minHeight = LumenSize.touchMin)
                    .clip(LumenShapes.small)
                    .clickable(enabled = !busy) { visible = !visible }
                    .padding(horizontal = LumenSpacing.sm, vertical = LumenSpacing.sm)
                    .testTag("github-token-visibility")
                    .semantics { contentDescription = if (visible) "hide token" else "show token" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (visible) "Hide" else "Show",
                    color = colors.dim,
                    fontFamily = Mono,
                    fontSize = LumenType.body,
                )
            }
        }

        Spacer(Modifier.height(LumenSpacing.md))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ActionPill(colors = colors, label = "Save", tag = "github-save", enabled = !busy) {
                keys.githubToken = token.trim().takeIf { it.isNotBlank() }
                keys.githubRepo = repo.trim()
                report(if (token.isBlank()) "Token cleared" else "Saved on this device", false)
            }
            Spacer(Modifier.width(LumenSpacing.sm))
            ActionPill(
                colors,
                if (busy) "Working\u2026" else "Test connection",
                "github-test",
                enabled = !busy && token.isNotBlank(),
            ) {
                scope.launch {
                    busy = true
                    report("Checking token\u2026", false)
                    when (val result = client.user(token.trim())) {
                        is GitHubUserStatus.Valid -> {
                            login = result.login
                            keys.githubToken = token.trim()
                            keys.githubLogin = result.login
                            report("Connected as ${result.login}", false)
                        }
                        GitHubUserStatus.Invalid -> report("Token rejected (401)", true)
                        GitHubUserStatus.NoPermission -> report("Token lacks permission (403)", true)
                        GitHubUserStatus.Unreachable -> report("Could not reach GitHub", true)
                    }
                    busy = false
                }
            }
        }

        status?.let { message ->
            Spacer(Modifier.height(LumenSpacing.md))
            StatusLine(colors, message, statusIsError)
        }

        Spacer(Modifier.height(LumenSpacing.xxl))
        SectionLabel(colors, "status")
        Spacer(Modifier.height(LumenSpacing.md))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(LumenShapes.card)
                .background(colors.surface)
                .border(1.dp, colors.outline, LumenShapes.card)
                .padding(horizontal = LumenSpacing.lg, vertical = LumenSpacing.md)
                .testTag("github-connected"),
            verticalArrangement = Arrangement.spacedBy(LumenSpacing.sm),
        ) {
            StatusRow(colors, "account", login.ifBlank { "not connected" })
            StatusRow(colors, "repository", repo.ifBlank { "none" })
            StatusRow(
                colors,
                "git",
                when (val p = gitProbe) {
                    null -> "not checked"
                    else -> p.version ?: "not installed"
                },
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ActionPill(colors, "Check git", "github-git-check", enabled = !busy) {
                    scope.launch {
                        busy = true
                        gitProbe = git.probe()
                        busy = false
                    }
                }
                if (gitProbe?.installed == false) {
                    Spacer(Modifier.width(LumenSpacing.sm))
                    ActionPill(colors, "Install git", "github-git-install", enabled = !busy) {
                        scope.launch {
                            busy = true
                            report("Installing git\u2026", false)
                            when (val r = git.installGit()) {
                                is GitResult.Ok -> report("Git installed", false)
                                is GitResult.Failed -> report(r.message, true)
                                GitResult.NotInstalled -> report("Git is not installed", true)
                            }
                            gitProbe = git.probe()
                            busy = false
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(LumenSpacing.xxl))
        SectionLabel(colors, "repository")
        Spacer(Modifier.height(LumenSpacing.md))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(LumenShapes.panel)
                .background(colors.surface)
                .border(1.dp, colors.outline, LumenShapes.panel)
                .padding(horizontal = LumenSpacing.md, vertical = 14.dp),
        ) {
            if (repo.isEmpty()) {
                Text(
                    GITHUB_URL_HINT,
                    color = colors.faint,
                    fontFamily = Mono,
                    fontSize = LumenType.bodyLarge,
                )
            }
            BasicTextField(
                value = repo,
                onValueChange = { repo = it },
                singleLine = true,
                enabled = !busy,
                textStyle = LocalTextStyle.current.copy(
                    color = colors.fg,
                    fontFamily = Mono,
                    fontSize = LumenType.bodyLarge,
                ),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(
                    autoCorrect = false,
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth().testTag("github-repo"),
            )
        }

        Spacer(Modifier.height(LumenSpacing.md))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(LumenSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionPill(
                colors,
                "Clone",
                "github-clone",
                enabled = !busy && token.isNotBlank() && repo.isNotBlank(),
            ) {
                val slug = repo.trim()
                if (slug.isBlank()) {
                    report("Enter a repo as $GITHUB_URL_HINT", true)
                } else {
                    scope.launch {
                        busy = true
                        report("Cloning\u2026", false)
                        val result = git.clone(slug, slug.substringAfter('/'), token.trim())
                        report(
                            when (result) {
                                is GitResult.Ok -> result.output
                                is GitResult.Failed -> result.message
                                GitResult.NotInstalled -> "Git is not installed"
                            },
                            result is GitResult.Failed || result == GitResult.NotInstalled,
                        )
                        busy = false
                    }
                }
            }
            ActionPill(colors, "Status", "github-status", enabled = !busy) {
                scope.launch {
                    busy = true
                    report(
                        when (val r = git.status()) {
                            is GitResult.Ok -> r.output
                            is GitResult.Failed -> r.message
                            GitResult.NotInstalled -> "Git is not installed"
                        },
                        false,
                    )
                    busy = false
                }
            }
            ActionPill(colors, "Open on GitHub", "github-open", enabled = repo.isNotBlank()) {
                val url = git.openUrl(repo.trim())
                if (url == null) {
                    report("Enter a repo as $GITHUB_URL_HINT", true)
                } else {
                    uriHandler.openUri(url)
                }
            }
            ActionPill(
                colors,
                "Commit",
                "git-commit",
                enabled = !busy && token.isNotBlank() && repo.isNotBlank(),
            ) { showCommit = true }
            ActionPill(
                colors,
                "Push",
                "git-push",
                enabled = !busy && token.isNotBlank() && repo.isNotBlank(),
            ) { showPush = true }
        }

        Spacer(Modifier.height(LumenSpacing.xxl))
        ActivitySection(
            colors = colors,
            activity = activity,
            refreshing = activityRefreshing,
            onRefresh = onRefreshActivity,
            loadDiff = loadDiff,
            git = git,
        )

        Spacer(Modifier.height(LumenSpacing.xxl))
        Text(
            "The token is sealed on-device and sent only to GitHub.\n" +
                "It is never written into the repository config.",
            color = colors.faint,
            fontFamily = Mono,
            fontSize = LumenType.caption,
            lineHeight = LumenType.lineTight,
        )
    }

    if (showCommit) {
        CommitDialog(
            colors = colors,
            onDismiss = { showCommit = false },
            onConfirm = { message ->
                showCommit = false
                onCommit(message)
            },
        )
    }
    if (showPush) {
        PushDialog(
            colors = colors,
            branch = activity?.branch.orEmpty(),
            onDismiss = { showPush = false },
            onConfirm = {
                showPush = false
                onPush()
            },
        )
    }
}

/**
 * Confirm dialog for a commit: an editable one-line summary (prefilled with
 * [DEFAULT_COMMIT_MESSAGE]) and a confirm action that hands the trimmed message
 * to the caller.
 */
@Composable
private fun CommitDialog(
    colors: LumenColors,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var message by rememberSaveable { mutableStateOf(DEFAULT_COMMIT_MESSAGE) }
    val valid = message.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.fg,
        textContentColor = colors.dim,
        title = {
            Text(
                "Commit changes",
                color = colors.fg,
                fontFamily = Mono,
                fontSize = LumenType.heading,
                fontWeight = FontWeight.Medium,
            )
        },
        text = {
            BasicTextField(
                value = message,
                onValueChange = { message = it },
                textStyle = TextStyle(
                    color = colors.fg,
                    fontFamily = Mono,
                    fontSize = LumenType.bodyLarge,
                ),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(
                    autoCorrect = false,
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(LumenShapes.inset)
                    .background(colors.bg)
                    .border(1.dp, colors.outline, LumenShapes.inset)
                    .padding(10.dp)
                    .testTag("git-commit-message"),
            )
        },
        confirmButton = {
            Text(
                "Commit",
                color = if (valid) colors.accent else colors.faint,
                fontFamily = Mono,
                fontSize = LumenType.body,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(LumenShapes.small)
                    .clickable(enabled = valid) { onConfirm(message.trim()) }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("git-commit-confirm"),
            )
        },
        dismissButton = {
            Text(
                "Cancel",
                color = colors.dim,
                fontFamily = Mono,
                fontSize = LumenType.body,
                modifier = Modifier
                    .clip(LumenShapes.small)
                    .clickable { onDismiss() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("git-commit-cancel"),
            )
        },
    )
}

/**
 * Confirm dialog for a push. The copy names the branch and states explicitly
 * that the push does not force, mirroring the guarded runner behaviour.
 */
@Composable
private fun PushDialog(
    colors: LumenColors,
    branch: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val target = branch.trim()
    val message = if (target.isBlank()) {
        "Push to origin? This does not force."
    } else {
        "Push to origin $target? This does not force."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.fg,
        textContentColor = colors.dim,
        title = {
            Text(
                "Push",
                color = colors.fg,
                fontFamily = Mono,
                fontSize = LumenType.heading,
                fontWeight = FontWeight.Medium,
            )
        },
        text = {
            Text(
                message,
                color = colors.dim,
                fontFamily = Mono,
                fontSize = LumenType.body,
                lineHeight = LumenType.lineTight,
            )
        },
        confirmButton = {
            Text(
                "Push",
                color = colors.accent,
                fontFamily = Mono,
                fontSize = LumenType.body,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(LumenShapes.small)
                    .clickable { onConfirm() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("git-push-confirm"),
            )
        },
        dismissButton = {
            Text(
                "Cancel",
                color = colors.dim,
                fontFamily = Mono,
                fontSize = LumenType.body,
                modifier = Modifier
                    .clip(LumenShapes.small)
                    .clickable { onDismiss() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("git-push-cancel"),
            )
        },
    )
}

@Composable
private fun SectionLabel(colors: LumenColors, text: String) {
    Text(
        text,
        color = colors.faint,
        fontFamily = Mono,
        fontSize = LumenType.caption,
        letterSpacing = 2.sp,
    )
}

@Composable
private fun ActivitySection(
    colors: LumenColors,
    activity: GitActivity?,
    refreshing: Boolean,
    onRefresh: (() -> Unit)?,
    loadDiff: (suspend (String) -> String)?,
    git: GitRunner,
) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf<String?>(null) }
    var diff by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(colors, "activity")
            Spacer(Modifier.weight(1f))
            ActionPill(
                colors,
                if (refreshing) "Refreshing\u2026" else "Refresh",
                "github-activity-refresh",
                enabled = onRefresh != null && !refreshing,
            ) { onRefresh?.invoke() }
        }
        Spacer(Modifier.height(LumenSpacing.md))
        val current = activity
        when {
            current == null -> Text(
                "No activity loaded",
                color = colors.faint,
                fontFamily = Mono,
                fontSize = LumenType.body,
                modifier = Modifier.testTag("github-activity-idle"),
            )
            !current.isRepo -> StateHint(
                colors = colors,
                text = "No local repository",
                detail = current.error,
                tag = "github-activity-empty",
            )
            else -> Column(
                Modifier
                    .fillMaxWidth()
                    .clip(LumenShapes.card)
                    .background(colors.surface)
                    .border(1.dp, colors.outline, LumenShapes.card)
                    .padding(horizontal = LumenSpacing.lg, vertical = LumenSpacing.md)
                    .testTag("github-activity"),
                verticalArrangement = Arrangement.spacedBy(LumenSpacing.sm),
            ) {
                StatusRow(colors, "branch", current.branch.ifBlank { "detached" })
                StatusRow(colors, "remote", current.remote.ifBlank { "none" })
                StatusRow(
                    colors,
                    "working",
                    if (current.dirty) "${current.changedFiles} changed" else "clean",
                )
                if (current.commits.isEmpty()) {
                    Text(
                        "no commits",
                        color = colors.faint,
                        fontFamily = Mono,
                        fontSize = LumenType.body,
                    )
                } else {
                    current.commits.forEach { commit ->
                        CommitRow(
                            colors = colors,
                            commit = commit,
                            expanded = expanded == commit.hash,
                            diff = if (expanded == commit.hash) diff else null,
                            loading = loading && expanded == commit.hash,
                            onToggle = {
                                if (expanded == commit.hash) {
                                    expanded = null
                                    diff = null
                                } else {
                                    expanded = commit.hash
                                    diff = null
                                    loading = true
                                    scope.launch {
                                        val text = loadDiff?.invoke(commit.hash)
                                            ?: describeDiff(git.commitDiff(commit.hash))
                                        if (expanded == commit.hash) {
                                            diff = text
                                            loading = false
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommitRow(
    colors: LumenColors,
    commit: GitCommit,
    expanded: Boolean,
    diff: String?,
    loading: Boolean,
    onToggle: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(LumenShapes.small)
                .clickable { onToggle() }
                .padding(vertical = LumenSpacing.xs)
                .testTag("github-commit")
                .semantics { contentDescription = commit.shortHash },
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                commit.shortHash,
                color = colors.accent,
                fontFamily = Mono,
                fontSize = LumenType.body,
                modifier = Modifier.width(64.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    commit.subject,
                    color = colors.fg,
                    fontFamily = Mono,
                    fontSize = LumenType.body,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${commit.author} \u00b7 ${commit.relativeTime}",
                    color = colors.faint,
                    fontFamily = Mono,
                    fontSize = LumenType.caption,
                )
            }
        }
        when {
            diff != null -> Text(
                diff,
                color = colors.dim,
                fontFamily = Mono,
                fontSize = LumenType.caption,
                lineHeight = LumenType.lineTight,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .padding(start = 64.dp, top = LumenSpacing.xs)
                    .testTag("github-diff"),
            )
            loading && expanded -> Text(
                "loading\u2026",
                color = colors.faint,
                fontFamily = Mono,
                fontSize = LumenType.caption,
                modifier = Modifier.padding(start = 64.dp, top = LumenSpacing.xs),
            )
        }
    }
}

/** Renders a commit-diff result as display text for the expanded row. */
private fun describeDiff(result: GitResult): String = when (result) {
    is GitResult.Ok -> result.output
    is GitResult.Failed -> result.message
    GitResult.NotInstalled -> "git is not installed"
}

@Composable
private fun ActionPill(
    colors: LumenColors,
    label: String,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(LumenShapes.pill)
            .background(colors.surface)
            .border(1.dp, colors.outline, LumenShapes.pill)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = LumenSpacing.lg, vertical = 10.dp)
            .testTag(tag),
    ) {
        Text(
            label,
            color = if (enabled) colors.dim else colors.faint,
            fontFamily = Mono,
            fontSize = LumenType.body,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun StatusRow(colors: LumenColors, label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = colors.faint,
            fontFamily = Mono,
            fontSize = LumenType.caption,
            letterSpacing = 1.sp,
            modifier = Modifier.width(84.dp),
        )
        Text(
            value,
            color = colors.fg,
            fontFamily = Mono,
            fontSize = LumenType.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusLine(colors: LumenColors, message: String, isError: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(LumenShapes.panel)
            .background(colors.surface)
            .border(1.dp, colors.outline, LumenShapes.panel)
            .padding(horizontal = LumenSpacing.md, vertical = LumenSpacing.md)
            .testTag("github-status-line")
            .semantics { contentDescription = message },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (isError) LumenAlert else colors.water))
        Spacer(Modifier.width(LumenSpacing.md))
        Text(
            message,
            color = colors.fg,
            fontFamily = Mono,
            fontSize = LumenType.body,
            lineHeight = LumenType.lineTight,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
