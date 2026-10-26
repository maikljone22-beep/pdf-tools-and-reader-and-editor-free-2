package com.smartdocs.editor.ui.tools5

import android.content.Intent
import android.graphics.Bitmap
import android.provider.ContactsContract
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.tools5.*
import com.smartdocs.editor.ui.common.LabeledSlider
import com.smartdocs.editor.ui.tools.ChoiceChips
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ScanFilter.label(): String = stringResource(
    when (this) {
        ScanFilter.ORIGINAL -> R.string.t5_f_original; ScanFilter.AUTO -> R.string.t5_f_auto; ScanFilter.GRAY -> R.string.t5_f_gray
        ScanFilter.BW -> R.string.t5_f_bw; ScanFilter.WHITEBOARD -> R.string.t5_f_board; ScanFilter.NO_SHADOW -> R.string.t5_f_shadow
        ScanFilter.RECEIPT -> R.string.t5_f_receipt
    },
)

/** Per-page editor: filter, brightness / contrast, rotation, order, delete; "apply to all pages". */
@Composable
fun ScanPageEditor(page: ScanPage, onApply: (PageEdit, Boolean) -> Unit, onDelete: () -> Unit, onMove: (Int) -> Unit, onDismiss: () -> Unit) {
    var edit by remember(page) { mutableStateOf(page.edit) }
    var all by remember { mutableStateOf(false) }
    val preview by produceState<Bitmap?>(null, page.file, edit) {
        value = withContext(Dispatchers.Default) { runCatching { ScanImage.preview(page.file, edit, 900) }.getOrNull() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.t5_edit_page)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    preview?.let { Image(it.asImageBitmap(), stringResource(R.string.t5_preview), Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) }
                        ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                ChoiceChips(ScanFilter.entries, edit.filter, { it.label() }) { edit = edit.copy(filter = it) }
                LabeledSlider(stringResource(R.string.t5_brightness), edit.brightness, -0.5f..0.5f, { edit = edit.copy(brightness = it) }) { "%+.0f%%".format(it * 100) }
                LabeledSlider(stringResource(R.string.t5_contrast), edit.contrast, 0.5f..2f, { edit = edit.copy(contrast = it) }) { "%.1f×".format(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { edit = edit.copy(rotation = (edit.rotation + 270) % 360) }) { Icon(Icons.Default.RotateLeft, stringResource(R.string.t5_rotate_left)) }
                    IconButton(onClick = { edit = edit.copy(rotation = (edit.rotation + 90) % 360) }) { Icon(Icons.Default.RotateRight, stringResource(R.string.t5_rotate_right)) }
                    IconButton(onClick = { onMove(-1) }) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.t5_move_up)) }
                    IconButton(onClick = { onMove(1) }) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.t5_move_down)) }
                    IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.t5_remove)) }
                }
                SwitchRow(stringResource(R.string.t5_apply_all), all) { all = it }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(edit, all) }) { Text(stringResource(R.string.t5_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Business card result: editable contact → Contacts app (insert intent) or a .vcf file. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactSheet(c0: Contact, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(c0.name) }
    var title by remember { mutableStateOf(c0.title) }
    var company by remember { mutableStateOf(c0.company) }
    var phones by remember { mutableStateOf(c0.phones.joinToString("\n") { it.number }) }
    var emails by remember { mutableStateOf(c0.emails.joinToString("\n")) }
    var web by remember { mutableStateOf(c0.websites.joinToString("\n")) }
    var address by remember { mutableStateOf(c0.address) }
    fun current(): Contact {
        val nums = phones.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val types = c0.phones.associate { it.number to it.type }
        return Contact(name.trim(), title.trim(), company.trim(), nums.map { Phone(BusinessCardParser.normalizePhone(it), types[BusinessCardParser.normalizePhone(it)] ?: PhoneType.OTHER) },
            emails.lines().map { it.trim() }.filter { it.isNotEmpty() }, web.lines().map { it.trim() }.filter { it.isNotEmpty() }, address.trim(), c0.note)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.t5_contact_title), style = MaterialTheme.typography.titleLarge)
            if (c0.isEmpty) Text(stringResource(R.string.t5_contact_empty), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.t5_c_name)) }, singleLine = true)
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.t5_c_job)) }, singleLine = true)
            OutlinedTextField(company, { company = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.t5_c_company)) }, singleLine = true)
            OutlinedTextField(phones, { phones = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.t5_c_phones)) })
            OutlinedTextField(emails, { emails = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.t5_c_emails)) })
            OutlinedTextField(web, { web = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.t5_c_web)) })
            OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.t5_c_address)) })
            Button(onClick = {
                val c = current()
                val i = Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
                    .putExtra(ContactsContract.Intents.Insert.NAME, c.name).putExtra(ContactsContract.Intents.Insert.COMPANY, c.company)
                    .putExtra(ContactsContract.Intents.Insert.JOB_TITLE, c.title).putExtra(ContactsContract.Intents.Insert.POSTAL, c.address)
                c.phones.getOrNull(0)?.let { i.putExtra(ContactsContract.Intents.Insert.PHONE, it.number) }
                c.phones.getOrNull(1)?.let { i.putExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE, it.number) }
                c.phones.getOrNull(2)?.let { i.putExtra(ContactsContract.Intents.Insert.TERTIARY_PHONE, it.number) }
                c.emails.getOrNull(0)?.let { i.putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
                c.emails.getOrNull(1)?.let { i.putExtra(ContactsContract.Intents.Insert.SECONDARY_EMAIL, it) }
                if (c.websites.isNotEmpty() || c.note.isNotBlank()) i.putExtra(ContactsContract.Intents.Insert.NOTES, (c.websites + c.note).filter { it.isNotBlank() }.joinToString("\n"))
                runCatching { ctx.startActivity(i) }
            }, Modifier.fillMaxWidth()) { Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.t5_add_contact)) }
            OutlinedButton(onClick = {
                scope.launch {
                    val c = current()
                    val f = withContext(Dispatchers.IO) { app.documents.uniqueFile(app.documents.docsDir, TextNorm.safeFileName(c.name.ifBlank { "contact" }), "vcf").also { it.writeText(VCard.write(c)) } }
                    shareFiles(ctx, app.documents, listOf(f), "text/x-vcard")
                }
            }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.t5_share_vcf)) }
        }
    }
}

/** Receipt result: merchant / date / total / currency → a row of the receipts CSV (Documents/receipts.csv). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptSheet(r0: Receipt, pdf: File, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var merchant by remember { mutableStateOf(r0.merchant) }
    var date by remember { mutableStateOf(r0.date ?: t5Today()) }
    var total by remember { mutableStateOf(r0.total.orEmpty()) }
    var currency by remember { mutableStateOf(r0.currency.orEmpty()) }
    var saved by remember { mutableStateOf<File?>(null) }
    val header = listOf(stringResource(R.string.t5_r_date), stringResource(R.string.t5_r_merchant), stringResource(R.string.t5_r_total), stringResource(R.string.t5_r_currency), stringResource(R.string.t5_r_file))
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.t5_receipt_title), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(merchant, { merchant = it }, Modifier.fillMaxWidth(), label = { Text(header[1]) }, singleLine = true)
            OutlinedTextField(date, { date = it }, Modifier.fillMaxWidth(), label = { Text(header[0]) }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(total, { total = it }, Modifier.weight(1f), label = { Text(header[2]) }, singleLine = true)
                OutlinedTextField(currency, { currency = it.uppercase().take(3) }, Modifier.weight(1f), label = { Text(header[3]) }, singleLine = true)
            }
            Button(onClick = {
                scope.launch {
                    saved = withContext(Dispatchers.IO) {
                        val f = File(app.documents.docsDir, "receipts.csv")
                        val fresh = !f.exists()
                        // UTF-8 BOM so spreadsheet apps read Arabic correctly
                        if (fresh) f.writeText("\uFEFF" + Csv.row(header) + "\r\n")
                        f.appendText(Csv.row(listOf(date, merchant, ReceiptParser.normalizeAmount(total) ?: total, currency, pdf.name)) + "\r\n")
                        f
                    }
                }
            }, Modifier.fillMaxWidth(), enabled = saved == null) { Icon(Icons.Default.TableChart, null); Spacer(Modifier.width(8.dp)); Text(stringResource(if (saved == null) R.string.t5_receipt_add else R.string.t5_receipt_added)) }
            OutlinedButton(onClick = { val f = File(app.documents.docsDir, "receipts.csv"); if (f.exists()) shareFiles(ctx, app.documents, listOf(f), "text/csv") }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.t5_receipt_share))
            }
        }
    }
}
