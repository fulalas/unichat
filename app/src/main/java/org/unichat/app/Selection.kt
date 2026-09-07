package org.unichat.app

/**
 * The tick state behind both multi-select lists (messages and chats). Keyed by
 * id and not by position, so a list that re-sorts or re-filters under the
 * selection keeps it; the row itself is kept as the value because a selected
 * row can leave the loaded window (a search re-query, a chat list filtered by
 * a term) and the action still has to act on it.
 */
class Selection<T>(
    private val rows: () -> List<T>,
    private val idOf: (T) -> String,
    private val notifyAt: (Int) -> Unit,
    private val onChanged: () -> Unit,
    private val canSelect: (T) -> Boolean = { true },
) : DragSelectAdapter {

    private val selected = LinkedHashMap<String, T>()

    val active: Boolean get() = selected.isNotEmpty()
    val count: Int get() = selected.size
    val values: Collection<T> get() = selected.values

    operator fun contains(id: String): Boolean = id in selected

    fun start(row: T): Boolean {
        if (!canSelect(row)) return false
        if (selected.put(idOf(row), row) == null) rebind(idOf(row))
        onChanged()
        return true
    }

    fun toggle(row: T) {
        if (!canSelect(row)) return
        val id = idOf(row)
        if (selected.remove(id) == null) selected[id] = row
        rebind(id)
        onChanged()
    }

    fun clear() {
        if (selected.isEmpty()) return
        val ids = HashSet(selected.keys)
        selected.clear()
        // one pass, not a linear scan per id: clearing a bulk drag-select in a
        // search window ran millions of comparisons in a frame
        rows().forEachIndexed { i, row -> if (idOf(row) in ids) notifyAt(i) }
        onChanged()
    }

    private fun rebind(id: String) {
        val i = rows().indexOfFirst { idOf(it) == id }
        if (i >= 0) notifyAt(i)
    }

    override fun setSelectedAt(pos: Int, sel: Boolean): Boolean {
        val row = rows().getOrNull(pos) ?: return false
        if (!canSelect(row)) return false
        val id = idOf(row)
        val changed = if (sel) selected.put(id, row) == null else selected.remove(id) != null
        if (changed) notifyAt(pos)
        return changed
    }

    override fun commitDragSelection() = onChanged()

    override fun snapshotSelection(): Set<String> = HashSet(selected.keys)

    override fun idAt(pos: Int): String = rows().getOrNull(pos)?.let(idOf) ?: ""
}
