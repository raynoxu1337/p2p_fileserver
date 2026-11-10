package com.raynoxu1337.p2pfileserver

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlin.concurrent.thread

private enum class IdDialog { NONE, REGENERATE, BACKUP_PASS, RESTORE_PASS, RESTORE_CONFIRM }

/** Runs [work] off the main thread and delivers the result on it. */
private fun <T> background(work: () -> T, done: (Result<T>) -> Unit) {
    val main = Handler(Looper.getMainLooper())
    thread(name = "identity") {
        val r = runCatching(work)
        main.post { done(r) }
    }
}

private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

private fun short(id: String) = if (id.length > 20) id.take(12) + "…" + id.takeLast(6) else id

/**
 * Advanced → Server identity: back up, restore, or replace the key behind
 * the server id. Every action that changes the id warns first.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IdentityTools(running: Boolean, currentId: String, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    var dialog by remember { mutableStateOf(IdDialog.NONE) }
    var busy by remember { mutableStateOf<String?>(null) }
    // Held only in memory between the dialog and the system file picker.
    var backupPass by remember { mutableStateOf<CharArray?>(null) }
    var restoreFile by remember { mutableStateOf<ByteArray?>(null) }
    var restoreKey by remember { mutableStateOf<ByteArray?>(null) }
    var restoreId by remember { mutableStateOf("") }

    val afterChange = { what: String ->
        if (running) ServerService.restart(app)
        onChanged()
        toast(ctx, what)
    }

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pass = backupPass
        backupPass = null
        if (uri == null || pass == null) { pass?.fill('\u0000'); return@rememberLauncherForActivityResult }
        busy = "Encrypting backup…"
        background({
            try {
                val enc = IdentityBackup.encrypt(Identity.keyBytes(app), pass)
                app.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(enc) }
            } finally {
                pass.fill('\u0000')
            }
        }) { r ->
            busy = null
            toast(ctx, r.exceptionOrNull()?.let { "Backup failed: ${it.message}" }
                ?: "Backup saved. Keep the file and its passphrase somewhere safe.")
        }
    }

    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        background({
            app.contentResolver.openInputStream(uri)!!.use { s ->
                val b = s.readNBytesCompat(IdentityBackup.MAX_FILE + 1)
                require(b.size <= IdentityBackup.MAX_FILE) { "Not a server identity backup file (too large)" }
                b
            }
        }) { r ->
            r.onSuccess { restoreFile = it; dialog = IdDialog.RESTORE_PASS }
                .onFailure { toast(ctx, "Could not read the file: ${it.message}") }
        }
    }

    Column {
        Label("Server identity")
        Hint("Generated on this phone on first run and stored only in the app's private storage. The invite token in your link is derived from it. Back it up to keep the same invite token on a new phone.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            FilledTonalButton(enabled = busy == null, onClick = { dialog = IdDialog.BACKUP_PASS }) { Text("Back up…") }
            FilledTonalButton(enabled = busy == null, onClick = { openDoc.launch(arrayOf("*/*")) }) { Text("Restore…") }
            OutlinedButton(
                enabled = busy == null,
                onClick = { dialog = IdDialog.REGENERATE },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Generate new identity") }
        }
        busy?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
    }

    when (dialog) {
        IdDialog.NONE -> {}

        IdDialog.REGENERATE -> AlertDialog(
            onDismissRequest = { dialog = IdDialog.NONE },
            title = { Text("Generate a new identity?") },
            text = {
                Text(
                    "This permanently replaces the server's key and gives it a new invite token.\n\n" +
                        "• The current link and QR code stop working for good. Everyone you shared them with needs the new link.\n" +
                        "• Open visitor sessions are disconnected" + (if (running) " and the server restarts." else ".") + "\n" +
                        "• This can't be undone unless you have a backup of the current identity.\n\n" +
                        "Only do this if the link leaked or you want to revoke access by link."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        dialog = IdDialog.NONE
                        busy = "Generating…"
                        background({ Identity.regenerate(app) }) { r ->
                            busy = null
                            r.onSuccess { afterChange("New identity created") }
                                .onFailure { toast(ctx, "Failed: ${it.message}") }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Replace identity") }
            },
            dismissButton = { TextButton(onClick = { dialog = IdDialog.NONE }) { Text("Cancel") } },
        )

        IdDialog.BACKUP_PASS -> PassphraseDialog(
            title = "Back up identity",
            text = "The backup is encrypted with this passphrase. Anyone who has both the file and the passphrase " +
                "can run a server with your invite token, so keep them apart and safe. There is no way to recover a " +
                "forgotten passphrase.",
            confirmLabel = "Choose file…",
            repeat = true,
            onDismiss = { dialog = IdDialog.NONE },
        ) { pass ->
            dialog = IdDialog.NONE
            backupPass = pass
            createDoc.launch("p2p-identity-${currentId.takeLast(8)}.p2pkey")
        }

        IdDialog.RESTORE_PASS -> PassphraseDialog(
            title = "Restore identity",
            text = "Enter the passphrase this backup was made with.",
            confirmLabel = "Decrypt",
            repeat = false,
            onDismiss = { dialog = IdDialog.NONE; restoreFile = null },
        ) { pass ->
            val file = restoreFile ?: return@PassphraseDialog
            dialog = IdDialog.NONE
            busy = "Decrypting backup…"
            background({
                try {
                    val key = IdentityBackup.decrypt(file, pass)
                    key to Identity.peerIdOf(app, key)
                } finally {
                    pass.fill('\u0000')
                }
            }) { r ->
                busy = null
                restoreFile = null
                r.onSuccess { (key, id) ->
                    if (id == currentId) {
                        toast(ctx, "This backup is the identity already in use.")
                    } else {
                        restoreKey = key; restoreId = id; dialog = IdDialog.RESTORE_CONFIRM
                    }
                }.onFailure { toast(ctx, it.message ?: "Restore failed") }
            }
        }

        IdDialog.RESTORE_CONFIRM -> AlertDialog(
            onDismissRequest = { dialog = IdDialog.NONE; restoreKey?.fill(0); restoreKey = null },
            title = { Text("Switch to the restored identity?") },
            text = {
                Text(
                    "Backup invite token:\n${short(restoreId)}\n\nCurrent invite token:\n${short(currentId)}\n\n" +
                        "The server will use the backup's invite token, so links made with it work again. The current " +
                        "identity is deleted; its link stops working unless you backed it up." +
                        (if (running) "\n\nThe server restarts." else "")
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val key = restoreKey ?: return@TextButton
                        dialog = IdDialog.NONE
                        restoreKey = null
                        busy = "Restoring…"
                        background({ try { Identity.install(app, key) } finally { key.fill(0) } }) { r ->
                            busy = null
                            r.onSuccess { afterChange("Identity restored") }
                                .onFailure { toast(ctx, "Restore failed: ${it.message}") }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Use restored identity") }
            },
            dismissButton = { TextButton(onClick = { dialog = IdDialog.NONE; restoreKey?.fill(0); restoreKey = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PassphraseDialog(
    title: String,
    text: String,
    confirmLabel: String,
    repeat: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val tooShort = pass.length < IdentityBackup.MIN_PASSPHRASE
    val mismatch = repeat && again != pass
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(text, modifier = Modifier.padding(bottom = 12.dp))
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it }, label = { Text("Passphrase") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = if (repeat && tooShort) ({ Text("At least ${IdentityBackup.MIN_PASSPHRASE} characters") }) else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (repeat) OutlinedTextField(
                    value = again, onValueChange = { again = it }, label = { Text("Repeat passphrase") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = again.isNotEmpty() && mismatch,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = pass.isNotEmpty() && (!repeat || (!tooShort && !mismatch)),
                onClick = { onConfirm(pass.toCharArray()) },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** InputStream.readNBytes is API 33+. */
private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < max) {
        val n = read(buf, 0, minOf(buf.size, max - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
