package org.unichat.app

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
