package com.example.boncommandechecker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

// ---------- Modèles ----------

data class PriceItem(
    val id: String = UUID.randomUUID().toString(),
    val reference: String,
    val name: String,
    val supplier: String,
    val unit: String,
    val price: Double
)

data class OrderLine(
    val id: String = UUID.randomUUID().toString(),
    val reference: String,
    val name: String,
    val quantity: Double,
    val orderPrice: Double,
    val referencePrice: Double?
)

data class ImportResult<T>(val values: List<T>, val message: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = PriceStore(this)
        setContent { MaterialTheme { App(store) } }
    }
}

class PriceStore(context: Context) {
    private val prefs = context.getSharedPreferences("price_store", Context.MODE_PRIVATE)

    fun load(): List<PriceItem> {
        val raw = prefs.getString("items", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        PriceItem(
                            id = o.getString("id"),
                            reference = o.getString("reference"),
                            name = o.getString("name"),
                            supplier = o.optString("supplier"),
                            unit = o.optString("unit", "u"),
                            price = o.getDouble("price")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(items: List<PriceItem>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("reference", it.reference)
                put("name", it.name)
                put("supplier", it.supplier)
                put("unit", it.unit)
                put("price", it.price)
            })
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(store: PriceStore) {
    var tab by remember { mutableIntStateOf(0) }
    var prices by remember { mutableStateOf(store.load()) }
    var tolerance by remember { mutableStateOf("1.0") }

    Scaffold(topBar = { TopAppBar(title = { Text("Contrôle des bons de commande") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Mercuriale") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Contrôle") })
            }
            if (tab == 0) {
                PriceListScreen(
                    items = prices,
                    onChange = { prices = it; store.save(it) }
                )
            } else {
                OrderCheckScreen(
                    prices = prices,
                    tolerance = tolerance,
                    onTolerance = { tolerance = it },
                    onPricesChange = { prices = it; store.save(it) }
                )
            }
        }
    }
}

// ---------- Mercuriale ----------

@Composable
fun PriceListScreen(items: List<PriceItem>, onChange: (List<PriceItem>) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var editing by remember { mutableStateOf<PriceItem?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            try {
                val result = importMercurial(context, uri)
                if (result.values.isNotEmpty()) {
                    val merged = mergeMercurial(items, result.values)
                    onChange(merged)
                }
                info = result.message
            } catch (e: Exception) {
                info = "Import impossible : ${e.message ?: "fichier non reconnu"}"
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Mercuriale", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = { editing = null; showDialog = true }) { Text("Ajouter") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !busy,
                onClick = { importLauncher.launch(arrayOf("text/csv", "text/*", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream")) }
            ) { Text(if (busy) "Import…" else "Importer CSV / Excel") }
        }
        Spacer(Modifier.height(8.dp))
        info?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text("${items.size} article(s). Les doublons de référence sont mis à jour à l'import.")
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items.sortedBy { it.reference.lowercase() }, key = { it.id }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${item.reference} — ${item.name}", fontWeight = FontWeight.Bold)
                        Text("${item.price.format2()} € / ${item.unit}" + if (item.supplier.isNotBlank()) " • ${item.supplier}" else "")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { editing = item; showDialog = true }) { Text("Modifier") }
                            TextButton(onClick = { onChange(items.filterNot { it.id == item.id }) }) { Text("Supprimer") }
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        PriceItemDialog(existing = editing, onDismiss = { showDialog = false }) { value ->
            val updated = if (editing == null) items + value else items.map { if (it.id == value.id) value else it }
            onChange(updated)
            showDialog = false
        }
    }
}

@Composable
fun PriceItemDialog(existing: PriceItem?, onDismiss: () -> Unit, onSave: (PriceItem) -> Unit) {
    var reference by remember { mutableStateOf(existing?.reference ?: "") }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var supplier by remember { mutableStateOf(existing?.supplier ?: "") }
    var unit by remember { mutableStateOf(existing?.unit ?: "u") }
    var price by remember { mutableStateOf(existing?.price?.toString() ?: "") }
    val valid = reference.isNotBlank() && name.isNotBlank() && price.toFrenchDoubleOrNull() != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Ajouter un article" else "Modifier l'article") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(reference, { reference = it }, label = { Text("Référence") }, singleLine = true)
                OutlinedTextField(name, { name = it }, label = { Text("Désignation") }, singleLine = true)
                OutlinedTextField(supplier, { supplier = it }, label = { Text("Fournisseur") }, singleLine = true)
                OutlinedTextField(unit, { unit = it }, label = { Text("Unité (u, kg, L…)") }, singleLine = true)
                OutlinedTextField(price, { price = it }, label = { Text("Prix de référence (€)") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = {
                onSave(
                    PriceItem(
                        existing?.id ?: UUID.randomUUID().toString(),
                        reference.trim(), name.trim(), supplier.trim(), unit.trim().ifBlank { "u" },
                        price.toFrenchDoubleOrNull()!!
                    )
                )
            }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

// ---------- Contrôle des bons ----------

@Composable
fun OrderCheckScreen(
    prices: List<PriceItem>,
    tolerance: String,
    onTolerance: (String) -> Unit,
    onPricesChange: (List<PriceItem>) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf(listOf<OrderLine>()) }
    var editing by remember { mutableStateOf<OrderLine?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var pendingCamera by remember { mutableStateOf(false) }
    var addToMercurialLine by remember { mutableStateOf<OrderLine?>(null) }

    val tol = tolerance.toFrenchDoubleOrNull() ?: 0.0
    val totalOrder = lines.sumOf { it.quantity * it.orderPrice }
    val totalRef = lines.sumOf { it.quantity * (it.referencePrice ?: it.orderPrice) }
    val over = lines.count { it.referencePrice != null && it.orderPrice > it.referencePrice * (1 + tol / 100.0) }

    fun applyDetected(text: String) {
        val known = parseOrderText(text, prices)
        val unknown = detectUnknownOrderLines(text, prices, known)
        val detected = known + unknown
        if (detected.isEmpty()) {
            info = "Texte lu, mais aucune ligne article exploitable n'a été détectée. Vous pouvez ajouter une ligne manuellement."
        } else {
            lines = mergeOrderLines(lines, detected)
            info = buildString {
                append("${known.size} ligne(s) rapprochée(s) de la mercuriale")
                if (unknown.isNotEmpty()) append(" • ${unknown.size} nouveau(x) produit(s) potentiel(s) à vérifier et ajouter")
                append(". Vérifiez les quantités et prix.")
            }
        }
    }

    val documentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    val text = if (context.getFileName(uri).lowercase().endsWith(".pdf")) {
                        recognizePdf(context, uri)
                    } else {
                        recognizeImageUri(context, uri)
                    }
                    applyDetected(text)
                } catch (e: Exception) {
                    info = "Lecture impossible : ${e.message ?: "document non reconnu"}"
                } finally {
                    busy = false
                }
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            busy = true
            scope.launch {
                try {
                    applyDetected(recognizeBitmap(bitmap))
                } catch (e: Exception) {
                    info = "Lecture de la photo impossible : ${e.message ?: "erreur OCR"}"
                } finally {
                    busy = false
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingCamera) cameraLauncher.launch(null)
        else if (!granted) info = "Autorisation caméra refusée. Vous pouvez importer une photo depuis vos fichiers."
        pendingCamera = false
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Vérification du bon", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(tolerance, onTolerance, label = { Text("Tolérance (%)") }, singleLine = true, modifier = Modifier.width(180.dp))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { editing = null; showAdd = true }) { Text("Ajouter") }
            OutlinedButton(enabled = !busy, onClick = { documentLauncher.launch(arrayOf("application/pdf", "image/*")) }) {
                Text(if (busy) "Analyse…" else "PDF / photo")
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    cameraLauncher.launch(null)
                } else {
                    pendingCamera = true
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }) { Text("Prendre une photo") }
            TextButton(onClick = { lines = emptyList(); info = null }) { Text("Vider") }
        }
        info?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Total commande : ${totalOrder.format2()} €", fontWeight = FontWeight.Bold)
                Text("Total de référence : ${totalRef.format2()} €")
                Text(if (over == 0) "Aucun dépassement détecté" else "$over ligne(s) au-dessus de la tolérance")
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(lines, key = { it.id }) { line ->
                val ref = line.referencePrice
                val pct = if (ref != null && ref != 0.0) ((line.orderPrice - ref) / ref) * 100.0 else null
                val status = when {
                    ref == null -> "Référence inconnue"
                    abs(pct ?: 0.0) <= tol -> "Conforme"
                    line.orderPrice > ref -> "Trop élevé"
                    else -> "Inférieur au prix de référence"
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${line.reference} — ${line.name}", fontWeight = FontWeight.Bold)
                        Text("Qté ${line.quantity.format2()} • BC ${line.orderPrice.format2()} € • Réf ${ref?.format2() ?: "—"} €")
                        Text(status + (pct?.let { " • ${if (it >= 0) "+" else ""}${it.format2()} %" } ?: ""), fontWeight = FontWeight.SemiBold)
                        Row {
                            TextButton(onClick = { editing = line; showAdd = true }) { Text("Corriger") }
                            if (ref == null) {
                                TextButton(onClick = { addToMercurialLine = line }) { Text("Ajouter à la mercuriale") }
                            }
                            TextButton(onClick = { lines = lines.filterNot { it.id == line.id } }) { Text("Supprimer") }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddOrderLineDialog(prices, existing = editing, onDismiss = { showAdd = false; editing = null }) { value ->
            lines = if (editing == null) lines + value else lines.map { if (it.id == value.id) value else it }
            showAdd = false
            editing = null
        }
    }

    addToMercurialLine?.let { line ->
        PriceFromOrderDialog(
            line = line,
            existingPrices = prices,
            onDismiss = { addToMercurialLine = null }
        ) { item ->
            val merged = mergeMercurial(prices, listOf(item))
            onPricesChange(merged)
            lines = lines.map {
                if (it.id == line.id) it.copy(reference = item.reference, name = item.name, referencePrice = item.price) else it
            }
            addToMercurialLine = null
            info = "${item.reference} ajouté à la mercuriale avec un prix de référence de ${item.price.format2()} €."
        }
    }
}

@Composable
fun AddOrderLineDialog(
    prices: List<PriceItem>,
    existing: OrderLine? = null,
    onDismiss: () -> Unit,
    onSave: (OrderLine) -> Unit
) {
    var reference by remember { mutableStateOf(existing?.reference ?: "") }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var quantity by remember { mutableStateOf(existing?.quantity?.toString() ?: "1") }
    var orderPrice by remember { mutableStateOf(existing?.orderPrice?.toString() ?: "") }
    val matched = prices.firstOrNull { it.reference.equals(reference.trim(), ignoreCase = true) }
    val valid = reference.isNotBlank() && quantity.toFrenchDoubleOrNull() != null && orderPrice.toFrenchDoubleOrNull() != null

    LaunchedEffect(matched?.id) { if (matched != null && existing == null) name = matched.name }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Ligne du bon de commande" else "Corriger la ligne") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(reference, { reference = it }, label = { Text("Référence article") }, singleLine = true)
                if (matched != null) Text("Prix mercuriale : ${matched.price.format2()} € / ${matched.unit}")
                OutlinedTextField(name, { name = it }, label = { Text("Désignation") }, singleLine = true)
                OutlinedTextField(quantity, { quantity = it }, label = { Text("Quantité") }, singleLine = true)
                OutlinedTextField(orderPrice, { orderPrice = it }, label = { Text("Prix unitaire du bon (€)") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = {
                onSave(
                    OrderLine(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        reference = reference.trim(),
                        name = name.ifBlank { matched?.name ?: "Article" },
                        quantity = quantity.toFrenchDoubleOrNull()!!,
                        orderPrice = orderPrice.toFrenchDoubleOrNull()!!,
                        referencePrice = matched?.price
                    )
                )
            }) { Text("Contrôler") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}


@Composable
fun PriceFromOrderDialog(
    line: OrderLine,
    existingPrices: List<PriceItem>,
    onDismiss: () -> Unit,
    onSave: (PriceItem) -> Unit
) {
    var reference by remember(line.id) { mutableStateOf(line.reference) }
    var name by remember(line.id) { mutableStateOf(line.name) }
    var supplier by remember(line.id) { mutableStateOf("") }
    var unit by remember(line.id) { mutableStateOf("u") }
    var price by remember(line.id) { mutableStateOf(line.orderPrice.toString()) }
    val duplicate = existingPrices.firstOrNull { it.reference.equals(reference.trim(), ignoreCase = true) }
    val valid = reference.isNotBlank() && name.isNotBlank() && price.toFrenchDoubleOrNull() != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajouter le produit à la mercuriale") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Les informations détectées sur le bon sont préremplies. Corrigez-les si nécessaire.")
                OutlinedTextField(reference, { reference = it }, label = { Text("Référence") }, singleLine = true)
                OutlinedTextField(name, { name = it }, label = { Text("Désignation") }, singleLine = true)
                OutlinedTextField(supplier, { supplier = it }, label = { Text("Fournisseur") }, singleLine = true)
                OutlinedTextField(unit, { unit = it }, label = { Text("Unité") }, singleLine = true)
                OutlinedTextField(price, { price = it }, label = { Text("Prix de référence (€)") }, singleLine = true)
                if (duplicate != null) {
                    Text("Cette référence existe déjà : l'enregistrement mettra à jour sa fiche.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = {
                onSave(
                    PriceItem(
                        id = duplicate?.id ?: UUID.randomUUID().toString(),
                        reference = reference.trim(),
                        name = name.trim(),
                        supplier = supplier.trim().ifBlank { duplicate?.supplier.orEmpty() },
                        unit = unit.trim().ifBlank { duplicate?.unit ?: "u" },
                        price = price.toFrenchDoubleOrNull()!!
                    )
                )
            }) { Text(if (duplicate == null) "Ajouter" else "Mettre à jour") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

// ---------- Import CSV/XLSX ----------

private fun importMercurial(context: Context, uri: Uri): ImportResult<PriceItem> {
    val name = context.getFileName(uri).lowercase(Locale.ROOT)
    val rows = context.contentResolver.openInputStream(uri)?.use { input ->
        if (name.endsWith(".xlsx")) readXlsx(input) else readCsv(input)
    } ?: return ImportResult(emptyList(), "Impossible d'ouvrir le fichier.")

    if (rows.isEmpty()) return ImportResult(emptyList(), "Fichier vide.")
    val headerIndex = rows.indexOfFirst { row -> row.any { normalizeHeader(it) in referenceHeaders } }
    if (headerIndex < 0) return ImportResult(emptyList(), "Colonne Référence introuvable. Utilisez Référence, Désignation, Fournisseur, Unité et Prix.")

    val headers = rows[headerIndex].map(::normalizeHeader)
    fun col(names: Set<String>) = headers.indexOfFirst { it in names }
    val refCol = col(referenceHeaders)
    val nameCol = col(nameHeaders)
    val supplierCol = col(supplierHeaders)
    val unitCol = col(unitHeaders)
    val priceCol = col(priceHeaders)
    if (refCol < 0 || priceCol < 0) return ImportResult(emptyList(), "Les colonnes Référence et Prix sont obligatoires.")

    val imported = rows.drop(headerIndex + 1).mapNotNull { row ->
        val ref = row.getOrNull(refCol)?.trim().orEmpty()
        val price = row.getOrNull(priceCol)?.toFrenchDoubleOrNull()
        if (ref.isBlank() || price == null) null else PriceItem(
            reference = ref,
            name = row.getOrNull(nameCol)?.trim().orEmpty().ifBlank { "Article $ref" },
            supplier = row.getOrNull(supplierCol)?.trim().orEmpty(),
            unit = row.getOrNull(unitCol)?.trim().orEmpty().ifBlank { "u" },
            price = price
        )
    }
    return ImportResult(imported, "${imported.size} article(s) importé(s) depuis ${context.getFileName(uri)}.")
}

private val referenceHeaders = setOf("reference", "ref", "code", "codearticle", "article")
private val nameHeaders = setOf("designation", "libelle", "nom", "description", "produit")
private val supplierHeaders = setOf("fournisseur", "supplier", "vendeur")
private val unitHeaders = setOf("unite", "unit", "u")
private val priceHeaders = setOf("prix", "prixunitaire", "prixreference", "pu", "tarif", "prixht")

private fun normalizeHeader(value: String): String = value.lowercase(Locale.ROOT)
    .replace("é", "e").replace("è", "e").replace("ê", "e").replace("ë", "e")
    .replace("à", "a").replace("â", "a").replace("ä", "a")
    .replace("î", "i").replace("ï", "i").replace("ô", "o").replace("ö", "o")
    .replace("ù", "u").replace("û", "u").replace("ü", "u").replace("ç", "c")
    .replace(Regex("[^a-z0-9]"), "")

private fun readCsv(input: InputStream): List<List<String>> {
    val lines = BufferedReader(InputStreamReader(input)).readLines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return emptyList()
    val delimiter = listOf(';', ',', '\t').maxByOrNull { d -> lines.first().count { it == d } } ?: ';'
    return lines.map { parseCsvLine(it, delimiter) }
}

private fun parseCsvLine(line: String, delimiter: Char): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { current.append('"'); i++ }
            c == '"' -> quoted = !quoted
            c == delimiter && !quoted -> { result += current.toString().trim(); current.clear() }
            else -> current.append(c)
        }
        i++
    }
    result += current.toString().trim()
    return result
}

private fun readXlsx(input: InputStream): List<List<String>> {
    val entries = mutableMapOf<String, ByteArray>()
    ZipInputStream(input).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory && (entry.name == "xl/sharedStrings.xml" || entry.name.startsWith("xl/worksheets/sheet"))) {
                entries[entry.name] = zip.readBytes()
            }
            zip.closeEntry()
        }
    }
    val shared = entries["xl/sharedStrings.xml"]?.inputStream()?.use(::parseSharedStrings) ?: emptyList()
    val sheet = entries.entries.filter { it.key.startsWith("xl/worksheets/sheet") }.minByOrNull { it.key }?.value
        ?: return emptyList()
    return parseSheet(sheet.inputStream(), shared)
}

private fun newXmlParser(input: InputStream): XmlPullParser = XmlPullParserFactory.newInstance().newPullParser().apply {
    setInput(input, "UTF-8")
}

private fun parseSharedStrings(input: InputStream): List<String> {
    val parser = newXmlParser(input)
    val result = mutableListOf<String>()
    var inSi = false
    var current = StringBuilder()
    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> if (parser.name == "si") { inSi = true; current = StringBuilder() }
            XmlPullParser.TEXT -> if (inSi) current.append(parser.text)
            XmlPullParser.END_TAG -> if (parser.name == "si") { result += current.toString(); inSi = false }
        }
        parser.next()
    }
    return result
}

private fun parseSheet(input: InputStream, shared: List<String>): List<List<String>> {
    val parser = newXmlParser(input)
    val rows = mutableListOf<MutableList<String>>()
    var row = mutableListOf<String>()
    var cellType = ""
    var cellRef = ""
    var value = ""
    var inValue = false
    var inInlineText = false
    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "row" -> row = mutableListOf()
                "c" -> { cellType = parser.getAttributeValue(null, "t") ?: ""; cellRef = parser.getAttributeValue(null, "r") ?: ""; value = "" }
                "v" -> inValue = true
                "t" -> if (cellType == "inlineStr") inInlineText = true
            }
            XmlPullParser.TEXT -> if (inValue || inInlineText) value += parser.text
            XmlPullParser.END_TAG -> when (parser.name) {
                "v" -> inValue = false
                "t" -> inInlineText = false
                "c" -> {
                    val col = excelColumnIndex(cellRef)
                    while (row.size <= col) row += ""
                    row[col] = if (cellType == "s") shared.getOrNull(value.toIntOrNull() ?: -1).orEmpty() else value
                }
                "row" -> if (row.any { it.isNotBlank() }) rows += row
            }
        }
        parser.next()
    }
    return rows
}

private fun excelColumnIndex(ref: String): Int {
    val letters = ref.takeWhile { it.isLetter() }.uppercase(Locale.ROOT)
    var value = 0
    letters.forEach { value = value * 26 + (it - 'A' + 1) }
    return (value - 1).coerceAtLeast(0)
}

private fun mergeMercurial(existing: List<PriceItem>, imported: List<PriceItem>): List<PriceItem> {
    val map = existing.associateBy { it.reference.trim().lowercase(Locale.ROOT) }.toMutableMap()
    imported.forEach { item ->
        val key = item.reference.trim().lowercase(Locale.ROOT)
        val old = map[key]
        map[key] = if (old == null) item else item.copy(id = old.id)
    }
    return map.values.toList()
}

// ---------- OCR photo / PDF ----------

private suspend fun recognizeImageUri(context: Context, uri: Uri): String {
    val image = InputImage.fromFilePath(context, uri)
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    return try { recognizer.process(image).awaitTask().text } finally { recognizer.close() }
}

private suspend fun recognizeBitmap(bitmap: Bitmap): String {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    return try { recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitTask().text } finally { recognizer.close() }
}

private suspend fun recognizePdf(context: Context, uri: Uri): String {
    val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return ""
    val renderer = PdfRenderer(descriptor)
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    return try {
        val text = StringBuilder()
        val pageCount = renderer.pageCount.coerceAtMost(12)
        for (i in 0 until pageCount) {
            val page = renderer.openPage(i)
            try {
                val maxDimension = maxOf(page.width, page.height).coerceAtLeast(1)
                val scale = minOf(2.0, 2400.0 / maxDimension.toDouble())
                val targetWidth = (page.width * scale).toInt().coerceAtLeast(1)
                val targetHeight = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitTask()
                text.append(result.text).append('\n')
                bitmap.recycle()
            } finally {
                page.close()
            }
        }
        text.toString()
    } finally {
        recognizer.close()
        renderer.close()
        descriptor.close()
    }
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

private fun parseOrderText(text: String, prices: List<PriceItem>): List<OrderLine> {
    if (text.isBlank() || prices.isEmpty()) return emptyList()
    val result = mutableListOf<OrderLine>()
    val normalizedPrices = prices.sortedByDescending { it.reference.length }

    text.lines().filter { it.isNotBlank() }.forEach { rawLine ->
        val cleanLine = rawLine.replace('€', ' ').replace("EUR", " ", ignoreCase = true)
        val item = normalizedPrices.firstOrNull { p -> containsReference(cleanLine, p.reference) } ?: return@forEach
        if (result.any { it.reference.equals(item.reference, true) }) return@forEach

        val numericLine = cleanLine.replace(item.reference, " ", ignoreCase = true)
        val nums = numberRegex.findAll(numericLine)
            .mapNotNull { it.value.toFrenchDoubleOrNull() }
            .filter { it >= 0.0 }
            .toList()
        if (nums.isEmpty()) return@forEach

        val plausiblePrices = nums.filter { it > 0.0 && it < 1_000_000 }
        val orderPrice = plausiblePrices.minByOrNull { abs(it - item.price) } ?: return@forEach
        val priceIndex = nums.indexOf(orderPrice)
        val quantityCandidates = nums.take(priceIndex).filter { it > 0.0 && it <= 100_000 }
        val quantity = quantityCandidates.lastOrNull() ?: 1.0

        result += OrderLine(
            reference = item.reference,
            name = item.name,
            quantity = quantity,
            orderPrice = orderPrice,
            referencePrice = item.price
        )
    }
    return result
}

private val numberRegex = Regex("(?<![A-Za-z0-9])\\d{1,7}(?:[ ,.']\\d{3})*(?:[,.]\\d{1,4})?(?![A-Za-z])")

private fun containsReference(line: String, reference: String): Boolean {
    val a = line.uppercase(Locale.ROOT).replace(Regex("\\s+"), "")
    val b = reference.uppercase(Locale.ROOT).replace(Regex("\\s+"), "")
    return b.length >= 2 && a.contains(b)
}

private fun detectUnknownOrderLines(
    text: String,
    prices: List<PriceItem>,
    alreadyMatched: List<OrderLine>
): List<OrderLine> {
    if (text.isBlank()) return emptyList()
    val knownRefs = prices.map { normalizeReference(it.reference) }.toSet()
    val matchedRefs = alreadyMatched.map { normalizeReference(it.reference) }.toSet()
    val seen = mutableSetOf<String>()
    val result = mutableListOf<OrderLine>()

    text.lines().map { it.trim() }.filter { it.length >= 5 }.forEach { raw ->
        val upper = raw.uppercase(Locale.ROOT)
        if (listOf("TOTAL", "SOUS TOTAL", "TVA", "TTC", "HT ", "ADRESSE", "TEL", "SIRET", "DATE", "COMMANDE").any { upper.contains(it) }) return@forEach
        if (prices.any { containsReference(raw, it.reference) }) return@forEach

        val numbers = numberRegex.findAll(raw.replace('€', ' '))
            .mapNotNull { match -> match.value.toFrenchDoubleOrNull()?.let { match.range to it } }
            .filter { it.second > 0.0 && it.second < 1_000_000 }
            .toList()
        if (numbers.isEmpty()) return@forEach

        val tokens = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{1,24}").findAll(raw).map { it.value }.toList()
        val ref = tokens.firstOrNull { token ->
            val n = normalizeReference(token)
            n.length >= 3 && n.any { it.isDigit() } && n.any { it.isLetter() } && n !in knownRefs && n !in matchedRefs
        } ?: tokens.firstOrNull { token ->
            val n = normalizeReference(token)
            n.length >= 4 && n.count { it.isDigit() } >= 3 && n !in knownRefs && n !in matchedRefs
        } ?: return@forEach

        val key = normalizeReference(ref)
        if (!seen.add(key)) return@forEach

        val values = numbers.map { it.second }
        val probablePrice = when {
            values.size >= 2 -> values.last()
            else -> values.first()
        }
        val probableQty = when {
            values.size >= 2 -> values.dropLast(1).lastOrNull { it > 0 && it <= 100_000 } ?: 1.0
            else -> 1.0
        }

        var designation = raw
            .replace(ref, " ", ignoreCase = true)
            .replace(numberRegex, " ")
            .replace("€", " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', ':', ';', '|')
        if (designation.length < 2) designation = "Produit détecté"
        if (designation.length > 90) designation = designation.take(90)

        result += OrderLine(
            reference = ref,
            name = designation,
            quantity = probableQty,
            orderPrice = probablePrice,
            referencePrice = null
        )
    }
    return result.take(40)
}

private fun normalizeReference(value: String): String = value.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")

private fun mergeOrderLines(existing: List<OrderLine>, imported: List<OrderLine>): List<OrderLine> {
    val result = existing.toMutableList()
    imported.forEach { line ->
        val idx = result.indexOfFirst { it.reference.equals(line.reference, true) }
        if (idx >= 0) result[idx] = line.copy(id = result[idx].id) else result += line
    }
    return result
}

// ---------- Utilitaires ----------

private fun Context.getFileName(uri: Uri): String {
    var name = "document"
    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx) ?: name
    }
    return name
}

private fun String.toFrenchDoubleOrNull(): Double? {
    var value = trim().replace("€", "").replace("\u00A0", "").replace(" ", "")
    if (value.isBlank()) return null
    val comma = value.lastIndexOf(',')
    val dot = value.lastIndexOf('.')
    value = when {
        comma >= 0 && dot >= 0 && comma > dot -> value.replace(".", "").replace(',', '.')
        comma >= 0 && dot >= 0 -> value.replace(",", "")
        comma >= 0 -> value.replace(',', '.')
        else -> value
    }
    return value.toDoubleOrNull()
}

private fun Double.format2(): String = String.format(Locale.FRANCE, "%.2f", this)
