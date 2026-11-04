package app.kamayoq.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.kamayoq.core.kavita.KavitaConnection
import app.kamayoq.core.kavita.RemoteLibrary
import app.kamayoq.core.kavita.normalizeKavitaAddress
import app.kamayoq.data.kavita.StoredKavitaKey
import java.io.IOException
import kotlinx.coroutines.launch

/** What the screen needs to mark libraries; its tests give it in memory. Failures throw [IOException]. */
interface KavitaLibraryChoice {
    suspend fun load(): List<RemoteLibrary>
    suspend fun marked(): Set<Int>

    /** Marks [libraries] and brings them to the Library. */
    suspend fun include(libraries: Set<Int>)
}

/** Receives the result ready, so its tests need no network; the address and key survive a refusal. */
@Composable
fun KavitaScreen(
    initialAddress: String,
    storedKey: StoredKavitaKey,
    connect: suspend (address: String, key: String) -> KavitaConnection,
    onBack: () -> Unit,
    keyDraft: MutableState<String> = remember {
        mutableStateOf((storedKey as? StoredKavitaKey.Present)?.key.orEmpty())
    },
    libraries: KavitaLibraryChoice? = null,
    // Not saved state: restored without its key after the process dies, it could pair with another.
    addressDraft: MutableState<String> = remember { mutableStateOf(initialAddress) },
) {
    val scope = rememberCoroutineScope()
    var address by addressDraft
    var key by keyDraft
    var connecting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<KavitaConnection?>(null) }
    // Saved apart from the result: turning the phone must not take the libraries away.
    var linked by rememberSaveable { mutableStateOf(false) }
    var triedAddress by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        Text(stringResource(R.string.kavita_connect), style = MaterialTheme.typography.headlineSmall)
        if (storedKey is StoredKavitaKey.Unreadable) {
            Text(stringResource(R.string.kavita_key_unreadable), Modifier.announced())
        }
        OutlinedTextField(
            value = address,
            // What Kavita shows for OPDS carries the key, and this field lives in the saved state.
            onValueChange = { address = it.substringBefore("/api/opds") },
            label = { Text(stringResource(R.string.kavita_address)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        if (address.trim().startsWith("http://", ignoreCase = true)) {
            Text(stringResource(R.string.kavita_http_warning))
        }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text(stringResource(R.string.kavita_key)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.kavita_key_hint))
        Button(
            enabled = address.isNotBlank() && key.isNotBlank() && !connecting,
            onClick = {
                connecting = true
                result = null
                triedAddress = normalizeKavitaAddress(address)
                scope.launch {
                    val answer = connect(triedAddress, key)
                    result = answer
                    linked = (answer as? KavitaConnection.Connected)?.keySaved == true
                    connecting = false
                }
            },
        ) {
            Text(stringResource(if (connecting) R.string.kavita_connecting else R.string.kavita_connect_button))
        }
        val message = when (val answer = result) {
            is KavitaConnection.Connected ->
                if (answer.keySaved) {
                    stringResource(R.string.kavita_connected, answer.version)
                } else {
                    stringResource(R.string.kavita_key_not_saved)
                }
            KavitaConnection.Refused -> stringResource(R.string.kavita_refused)
            KavitaConnection.Unreachable -> stringResource(R.string.kavita_unreachable, triedAddress)
            KavitaConnection.NotKavita -> stringResource(R.string.kavita_not_kavita, triedAddress)
            KavitaConnection.OtherAccount -> stringResource(R.string.kavita_other_account)
            null -> null
        }
        if (message != null) Text(message, Modifier.announced())
        // A key not kept changed nothing here, and the libraries would be the stored server's.
        if (linked && libraries != null) KavitaLibraries(libraries)
    }
}

private val MARKED_SAVER = listSaver<Set<Int>, Int>(save = { it.toList() }, restore = { it.toSet() })

@Composable
private fun KavitaLibraries(choice: KavitaLibraryChoice) {
    val scope = rememberCoroutineScope()
    var available by remember { mutableStateOf<List<RemoteLibrary>?>(null) }
    var marked by rememberSaveable(stateSaver = MARKED_SAVER) { mutableStateOf(emptySet<Int>()) }
    var markedBefore by rememberSaveable(stateSaver = MARKED_SAVER) { mutableStateOf(emptySet<Int>()) }
    // A recreated screen keeps the marks the person changed and only lists the libraries again.
    var markedRead by rememberSaveable { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var including by remember { mutableStateOf(false) }
    var included by remember { mutableStateOf(false) }

    LaunchedEffect(choice) {
        try {
            if (!markedRead) {
                marked = choice.marked()
                markedBefore = marked
                markedRead = true
            }
            available = choice.load()
        } catch (e: IOException) {
            failed = true
        }
    }

    available?.let { list ->
        Text(stringResource(R.string.kavita_libraries), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.kavita_include_hint))
        list.forEach { library ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(library.id in marked, role = Role.Checkbox) { on ->
                        included = false
                        marked = if (on) marked + library.id else marked - library.id
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // The whole row is the control: TalkBack reads one checkbox, with the name.
                Checkbox(checked = library.id in marked, onCheckedChange = null)
                Text(library.name)
            }
        }
        Button(
            // Empty is an answer too, once something was marked before.
            enabled = (marked.isNotEmpty() || markedBefore.isNotEmpty()) && !including,
            onClick = {
                including = true
                failed = false
                scope.launch {
                    try {
                        choice.include(marked)
                        included = true
                        markedBefore = marked
                    } catch (e: IOException) {
                        failed = true
                    } finally {
                        including = false
                    }
                }
            },
        ) {
            Text(stringResource(if (including) R.string.kavita_including else R.string.kavita_include))
        }
    }
    if (included) Text(stringResource(R.string.kavita_included), Modifier.announced())
    if (failed) Text(stringResource(R.string.kavita_libraries_failed), Modifier.announced())
}
