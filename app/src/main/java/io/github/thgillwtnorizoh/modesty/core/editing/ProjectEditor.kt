package io.github.thgillwtnorizoh.modesty.core.editing

import io.github.thgillwtnorizoh.modesty.core.model.AudioProject
import java.util.ArrayDeque

class ProjectEditor(initialProject: AudioProject) {
    data class HistoryEntry(
        val before: AudioProject,
        val after: AudioProject,
        val description: String,
    )

    private data class Transaction(
        val before: AudioProject,
        val description: String,
    )

    private val undoStack = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()
    private var transaction: Transaction? = null

    var project: AudioProject = initialProject.validate()
        private set

    val canUndo: Boolean
        get() = undoStack.isNotEmpty() && transaction == null

    val canRedo: Boolean
        get() = redoStack.isNotEmpty() && transaction == null

    val undoCount: Int
        get() = undoStack.size

    fun apply(operation: EditOperation) {
        val before = project
        val after = operation.applyTo(before).validate()
        if (after == before) return

        project = after
        if (transaction == null) {
            undoStack.addLast(HistoryEntry(before, after, operation.description))
            redoStack.clear()
        }
    }

    fun beginTransaction(description: String) {
        require(transaction == null) { "An edit transaction is already active" }
        require(description.isNotBlank())
        transaction = Transaction(project, description)
    }

    fun commitTransaction() {
        val active = requireNotNull(transaction) { "No edit transaction is active" }
        transaction = null
        if (active.before != project) {
            undoStack.addLast(HistoryEntry(active.before, project, active.description))
            redoStack.clear()
        }
    }

    fun rollbackTransaction() {
        val active = requireNotNull(transaction) { "No edit transaction is active" }
        project = active.before
        transaction = null
    }

    fun undo(): Boolean {
        check(transaction == null) { "Cannot undo during an active edit transaction" }
        val entry = undoStack.pollLast() ?: return false
        project = entry.before
        redoStack.addLast(entry)
        return true
    }

    fun redo(): Boolean {
        check(transaction == null) { "Cannot redo during an active edit transaction" }
        val entry = redoStack.pollLast() ?: return false
        project = entry.after
        undoStack.addLast(entry)
        return true
    }
}
