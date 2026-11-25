package dev.superair.glasses

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.superair.Air
import dev.superair.AppEntry
import org.json.JSONArray
import org.json.JSONObject

/**
 * The launcher: one arrangement of pages, shared by the glasses and the cover.
 *
 * Every page is five by two slots, and a slot holds an app, a folder, or nothing — positions are
 * kept exactly where they were put. The cover is where the arranging happens (long-press, drag,
 * drop on an app to make a folder); the glasses show the same pages, and both follow [page] and
 * [openFolder], so the phone and the glasses are always looking at the same thing.
 *
 * The order buttons are views over that arrangement, not edits to it: A–Z, RECENT and MOST USED
 * list every app flat, and CUSTOM is the arrangement itself. Search, likewise, is a flat view.
 */
object Home {
    sealed interface Item {
        data class App(val key: String) : Item
        data class Folder(val id: Long, val name: String, val apps: List<String>) : Item
    }

    enum class Sort(val title: String) { CUSTOM("CUSTOM"), NAME("A–Z"), RECENT("RECENT"), MOST("MOST USED") }

    const val COLUMNS = 5
    const val ROWS = 2
    const val PER_PAGE = COLUMNS * ROWS

    var searching by mutableStateOf(false)
    var query by mutableStateOf("")
    var page by mutableIntStateOf(0)
    var settingsOpen by mutableStateOf(false)
    var notificationsOpen by mutableStateOf(false)
    var openFolder by mutableStateOf<Long?>(null)
    /** The folder whose name the cover keyboard is typing, if any. */
    var renaming by mutableStateOf<Long?>(null)

    /** The arrangement: pages of [PER_PAGE] slots. */
    var pages by mutableStateOf<List<List<Item?>>>(listOf(List(PER_PAGE) { null })); private set

    var sort by mutableStateOf(Sort.CUSTOM); private set
    var clock24 by mutableStateOf(true); private set
    var fahrenheit by mutableStateOf(false); private set
    var labels by mutableStateOf(true); private set

    private var prefs: SharedPreferences? = null
    private var context: Context? = null
    private val launches = HashMap<String, Int>()
    private val lastOpened = HashMap<String, Long>()
    private var known: Set<String> = emptySet()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("home", Context.MODE_PRIVATE)
        prefs = p
        this.context = context.applicationContext
        sort = Sort.entries.firstOrNull { it.name == p.getString("sort2", null) } ?: Sort.CUSTOM
        clock24 = p.getBoolean("clock24", android.text.format.DateFormat.is24HourFormat(context))
        fahrenheit = p.getBoolean("fahrenheit", false)
        labels = p.getBoolean("labels", true)
        p.all.forEach { (k, v) ->
            if (k.startsWith("n:") && v is Int) launches[k.removePrefix("n:")] = v
            if (k.startsWith("t:") && v is Long) lastOpened[k.removePrefix("t:")] = v
        }
        p.getString("layout", null)?.let { pages = decode(it) }
        reconcile()
    }

    // ------------------------------------------------------------------ settings

    fun nextSort() { sort = Sort.entries[(sort.ordinal + 1) % Sort.entries.size]; page = 0; prefs?.edit()?.putString("sort2", sort.name)?.apply() }
    fun toggleClock() { clock24 = !clock24; prefs?.edit()?.putBoolean("clock24", clock24)?.apply() }
    fun toggleUnits() { fahrenheit = !fahrenheit; prefs?.edit()?.putBoolean("fahrenheit", fahrenheit)?.apply(); Weather.refresh(force = true) }
    fun toggleLabels() { labels = !labels; prefs?.edit()?.putBoolean("labels", labels)?.apply() }

    // ------------------------------------------------------------------ what is shown

    fun key(app: AppEntry) = app.pkg + "/" + app.activity
    fun app(key: String): AppEntry? = Air.apps.firstOrNull { key(it) == key }

    /** True when the shown pages are the arrangement, so slots may be empty and may be edited. */
    val arranged: Boolean get() = sort == Sort.CUSTOM && !searching

    /** The pages as shown right now. */
    fun shown(): List<List<Item?>> {
        if (arranged) return pages
        val q = query.trim()
        val apps = Air.apps.filter { q.isEmpty() || it.label.contains(q, ignoreCase = true) }
        val ordered = when (sort) {
            Sort.RECENT -> apps.sortedWith(compareByDescending<AppEntry> { lastOpened[key(it)] ?: 0L }.thenBy { it.label.lowercase() })
            Sort.MOST -> apps.sortedWith(compareByDescending<AppEntry> { launches[key(it)] ?: 0 }.thenBy { it.label.lowercase() })
            else -> apps.sortedBy { it.label.lowercase() }
        }
        val items = ordered.map { Item.App(key(it)) }
        if (items.isEmpty()) return listOf(List(PER_PAGE) { null })
        return items.chunked(PER_PAGE).map { it + List(PER_PAGE - it.size) { null } }
    }

    /**
     * Where each slot of a row is drawn, in columns from the left.
     *
     * A row of exactly four sits centred under the five above it, half a column in, so it reads as
     * a deliberate shorter row rather than a full one with a hole at the end. Its empty slot is not
     * drawn at all (null). Any other row keeps every slot where it is, gaps included.
     */
    fun rowPositions(row: List<Item?>): List<Float?> {
        if (row.count { it != null } != COLUMNS - 1) return row.indices.map { it.toFloat() }
        var n = 0
        return row.map { if (it == null) null else 0.5f + n++ }
    }

    fun folder(id: Long): Item.Folder? =
        pages.flatten().filterIsInstance<Item.Folder>().firstOrNull { it.id == id }

    // ------------------------------------------------------------------ keeping it whole

    /** Installed apps that are not anywhere go into the first empty slots; gone apps leave. */
    fun reconcile() {
        val installed = Air.apps.map(::key).toSet()
        if (installed.isEmpty() || installed == known) return
        // First time with apps to show: start from the folders you keep on the phone.
        if (prefs?.contains("layout") == false) { known = installed; commit(defaultLayout()); return }
        known = installed
        var changed = false
        val placed = HashSet<String>()
        var next = pages.map { slots ->
            slots.map { item ->
                when (item) {
                    is Item.App -> if (item.key in installed && placed.add(item.key)) item else { changed = true; null }
                    is Item.Folder -> {
                        val apps = item.apps.filter { it in installed && placed.add(it) }
                        if (apps.size != item.apps.size) changed = true
                        when (apps.size) { 0 -> null; 1 -> Item.App(apps[0]); else -> item.copy(apps = apps) }
                    }
                    null -> null
                }
            }
        }
        val missing = Air.apps.sortedBy { it.label.lowercase() }.map(::key).filter { it !in placed }
        if (missing.isNotEmpty()) {
            changed = true
            val flat = next.flatten().toMutableList()
            var i = 0
            for (k in missing) {
                while (i < flat.size && flat[i] != null) i++
                if (i < flat.size) flat[i] = Item.App(k) else flat.add(Item.App(k))
            }
            while (flat.size % PER_PAGE != 0) flat.add(null)
            next = flat.chunked(PER_PAGE)
        }
        if (changed) commit(next)
    }

    private fun commit(next: List<List<Item?>>) {
        var trimmed = next
        while (trimmed.size > 1 && trimmed.last().all { it == null }) trimmed = trimmed.dropLast(1)
        if (trimmed.isEmpty()) trimmed = listOf(List(PER_PAGE) { null })
        pages = trimmed
        if (page >= pages.size) page = pages.size - 1
        prefs?.edit()?.putString("layout", encode(trimmed))?.apply()
    }

    // ------------------------------------------------------------------ editing

    private fun at(page: Int, slot: Int): Item? = pages.getOrNull(page)?.getOrNull(slot)

    private fun withSlot(source: List<List<Item?>>, page: Int, slot: Int, item: Item?): List<List<Item?>> {
        val grown = if (page >= source.size) source + List(page - source.size + 1) { List(PER_PAGE) { null } } else source
        return grown.mapIndexed { p, slots -> if (p != page) slots else slots.mapIndexed { s, it -> if (s == slot) item else it } }
    }

    /**
     * Drop what was at (fromPage, fromSlot) on (toPage, toSlot): into an empty slot it moves; onto
     * another app it makes a folder of the two; onto a folder it joins it; onto itself, nothing.
     * [intoItem] false means the drop was beside the target, not on it: the two swap.
     */
    fun drop(fromPage: Int, fromSlot: Int, toPage: Int, toSlot: Int, intoItem: Boolean) {
        if (fromPage == toPage && fromSlot == toSlot) return
        val moving = at(fromPage, fromSlot) ?: return
        val target = at(toPage, toSlot)
        var next = withSlot(pages, fromPage, fromSlot, null)
        next = when {
            target == null -> withSlot(next, toPage, toSlot, moving)
            !intoItem || moving is Item.Folder -> withSlot(withSlot(next, toPage, toSlot, moving), fromPage, fromSlot, target)
            target is Item.Folder -> withSlot(next, toPage, toSlot, target.copy(apps = target.apps + (moving as Item.App).key))
            else -> withSlot(next, toPage, toSlot,
                Item.Folder(System.currentTimeMillis(), "Folder", listOf((target as Item.App).key, (moving as Item.App).key)))
        }
        sort = Sort.CUSTOM
        commit(next)
    }

    /** Take an app out of its folder and put it in the first empty slot from the current page on. */
    fun takeOut(folderId: Long, appKey: String) {
        var next = pages.map { slots ->
            slots.map { item ->
                if (item is Item.Folder && item.id == folderId) {
                    val apps = item.apps - appKey
                    when (apps.size) { 0 -> null; 1 -> Item.App(apps[0]); else -> item.copy(apps = apps) }
                } else item
            }
        }
        val flat = next.flatten().toMutableList()
        var i = flat.indices.firstOrNull { it >= page * PER_PAGE && flat[it] == null }
        if (i == null) { while (flat.size % PER_PAGE != 0 || flat.isEmpty()) flat.add(null); flat.addAll(List(PER_PAGE) { null }); i = flat.indexOfFirst { it == null } }
        flat[i] = Item.App(appKey)
        while (flat.size % PER_PAGE != 0) flat.add(null)
        next = flat.chunked(PER_PAGE)
        if (folder(folderId)?.apps?.size?.let { it <= 2 } == true) openFolder = null
        commit(next)
    }

    fun rename(folderId: Long, name: String) {
        commit(pages.map { slots -> slots.map { if (it is Item.Folder && it.id == folderId) it.copy(name = name) else it } })
    }

    /** Back to the default folders, everything else loose after them. */
    fun resetToDefault() { if (Air.apps.isNotEmpty()) { page = 0; openFolder = null; commit(defaultLayout()) } }

    /**
     * The phone's own organisation, as its owner keeps it: Google, Samsung, My apps, Media and Tools
     * as folders on the first page, and every other app loose after them, A to Z, to be put where
     * it belongs by hand. (Samsung's launcher keeps its real layout behind a permission only it and
     * the system have, so it cannot simply be read.)
     */
    private fun defaultLayout(): List<List<Item?>> {
        val groups = linkedMapOf("Google" to mutableListOf<String>(), "Samsung" to mutableListOf(),
            "My apps" to mutableListOf(), "Media" to mutableListOf(), "Tools" to mutableListOf())
        val loose = mutableListOf<String>()
        for (app in Air.apps.sortedBy { it.label.lowercase() }) {
            val group = groupOf(app)
            if (group == null) loose += key(app) else groups.getValue(group) += key(app)
        }
        var id = System.currentTimeMillis()
        val items = mutableListOf<Item?>()
        for ((name, apps) in groups) when (apps.size) {
            0 -> Unit
            1 -> loose.add(0, apps[0])
            else -> items += Item.Folder(id++, name, apps)
        }
        while (items.size % COLUMNS != 0) items += null
        items += loose.map { Item.App(it) }
        while (items.size % PER_PAGE != 0 || items.isEmpty()) items += null
        return items.chunked(PER_PAGE)
    }

    private val mediaWords = listOf("music", "spotify", "youtube", "netflix", "video", "tv", "podcast", "radio",
        "photos", "gallery", "player", "twitch", "disney", "prime", "deezer", "tidal", "soundcloud", "plex", "vlc")
    private val toolWords = listOf("tool", "launcher", "file", "calculator", "adblock", "vpn", "wavelet",
        "good lock", "goodlock", "termux", "manager", "assistant", "cover", "tweak", "widget", "keyboard", "clock")

    private fun groupOf(app: AppEntry): String? {
        val pkg = app.pkg
        val label = app.label.lowercase()
        if (mine(pkg)) return "My apps"
        if (mediaWords.any { it in label || it in pkg }) return "Media"
        if (pkg.startsWith("com.google.") || pkg == "com.android.chrome" || pkg == "com.android.vending") return "Google"
        if (pkg.startsWith("com.samsung.") || pkg.startsWith("com.sec.") || pkg.startsWith("com.osp.")) return "Samsung"
        if (toolWords.any { it in label || it in pkg }) return "Tools"
        return null
    }

    /** An app its owner built: debuggable, or installed by hand rather than from a store. */
    private fun mine(pkg: String): Boolean {
        val pm = context?.packageManager ?: return false
        return runCatching {
            val info = pm.getApplicationInfo(pkg, 0)
            if (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0) return false
            if (info.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) return true
            val installer = pm.getInstallSourceInfo(pkg).installingPackageName
            installer == null || installer == "com.android.shell" || installer == "com.android.packageinstaller" ||
                installer == "com.google.android.packageinstaller" || installer == "com.samsung.android.packageinstaller"
        }.getOrDefault(false)
    }

    /** One more page to drag things onto. */
    fun addPage() { commit(pages + listOf(List(PER_PAGE) { null })); }

    // ------------------------------------------------------------------ opening

    fun opened(app: AppEntry) {
        val k = key(app)
        val n = (launches[k] ?: 0) + 1
        val t = System.currentTimeMillis()
        launches[k] = n; lastOpened[k] = t
        prefs?.edit()?.putInt("n:$k", n)?.putLong("t:$k", t)?.apply()
        endSearch()
        openFolder = null
    }

    fun launch(app: AppEntry) { opened(app); Air.open(app) }

    fun startSearch() { searching = true; settingsOpen = false; notificationsOpen = false; openFolder = null; page = 0 }

    fun toggleNotifications() {
        notificationsOpen = !notificationsOpen
        if (notificationsOpen) { settingsOpen = false; endSearch() }
    }

    fun toggleSettings() {
        settingsOpen = !settingsOpen
        if (settingsOpen) { notificationsOpen = false; endSearch() }
    }
    fun endSearch() { searching = false; query = ""; page = 0 }

    /** Settings → Turn off: everything stops, and it stays off until turned on from the cover. */
    fun turnOff() {
        val context = context ?: return
        dev.superair.HostLauncher.of(context).turnOff()
    }

    // ------------------------------------------------------------------ typing

    /** Typing from the cover keyboard while the search or a folder's name is being typed. */
    fun type(text: String): Boolean {
        renaming?.let { id -> folder(id)?.let { rename(id, (it.name + text).take(24)) }; return true }
        if (!searching) return false
        query += text.replace("\n", ""); page = 0
        return true
    }

    fun key(code: Int): Boolean {
        renaming?.let { id ->
            when (code) {
                android.view.KeyEvent.KEYCODE_DEL -> folder(id)?.let { rename(id, it.name.dropLast(1)) }
                android.view.KeyEvent.KEYCODE_ENTER -> renaming = null
            }
            return true
        }
        if (!searching) return false
        when (code) {
            android.view.KeyEvent.KEYCODE_DEL -> { query = query.dropLast(1); page = 0 }
            android.view.KeyEvent.KEYCODE_ENTER -> shown().flatten().filterIsInstance<Item.App>().firstOrNull()?.let { app(it.key) }?.let(::launch)
        }
        return true
    }

    // ------------------------------------------------------------------ saving

    private fun encode(pages: List<List<Item?>>): String = JSONArray().apply {
        for (slots in pages) put(JSONArray().apply {
            for (item in slots) put(when (item) {
                null -> JSONObject.NULL
                is Item.App -> item.key
                is Item.Folder -> JSONObject().put("id", item.id).put("name", item.name).put("apps", JSONArray(item.apps))
            })
        })
    }.toString()

    private fun decode(text: String): List<List<Item?>> = runCatching {
        val array = JSONArray(text)
        (0 until array.length()).map { p ->
            val slots = array.getJSONArray(p)
            (0 until PER_PAGE).map { s ->
                val v = if (s < slots.length()) slots.opt(s) else null
                when (v) {
                    is String -> Item.App(v)
                    is JSONObject -> {
                        val apps = v.getJSONArray("apps")
                        Item.Folder(v.getLong("id"), v.optString("name", "Folder"), (0 until apps.length()).map { apps.getString(it) })
                    }
                    else -> null
                }
            }
        }.ifEmpty { listOf(List(PER_PAGE) { null }) }
    }.getOrDefault(listOf(List(PER_PAGE) { null }))
}
