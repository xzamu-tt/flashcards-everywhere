/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 *
 * Builds a collapsible tree from the flat deck list returned by AnkiDroid's
 * ContentProvider.  The "::" convention in deck names encodes parent→child
 * relationships (e.g. "Languages::Spanish::Verbs").
 */
package com.flashcardseverywhere.data.anki

/** A node in the deck hierarchy.  Children are sorted alphabetically. */
data class DeckTreeNode(
    val deck: DeckRow?,          // null for synthetic parents that don't exist as real decks
    val fullName: String,
    val leafName: String,
    val depth: Int,
    val children: List<DeckTreeNode>,
) {
    val hasChildren: Boolean get() = children.isNotEmpty()
    val id: Long get() = deck?.id ?: fullName.hashCode().toLong()
}

/** A single visible row after flattening the tree according to expand state. */
data class FlatDeckItem(
    val node: DeckTreeNode,
    val depth: Int,
    val isExpanded: Boolean,
    val hasChildren: Boolean,
)

/**
 * Build a tree from a flat list of [DeckRow]s.
 *
 * If the API omits an intermediate parent (e.g. returns "A::B::C" but not "A"
 * or "A::B"), synthetic placeholder nodes are created so the tree is always
 * well-formed.
 */
fun buildDeckTree(decks: List<DeckRow>): List<DeckTreeNode> {
    if (decks.isEmpty()) return emptyList()

    // Index real decks by fullName for O(1) lookup.
    val byFullName: Map<String, DeckRow> = decks.associateBy { it.fullName }

    // Collect all unique full-name paths that need a node (real + synthetic).
    val allPaths = mutableSetOf<String>()
    for (deck in decks) {
        val parts = deck.fullName.split("::")
        for (i in parts.indices) {
            allPaths += parts.subList(0, i + 1).joinToString("::")
        }
    }

    // Group paths by parent path.
    val childrenOf = mutableMapOf<String?, MutableList<String>>()
    for (path in allPaths) {
        val parts = path.split("::")
        val parentPath = if (parts.size > 1) parts.dropLast(1).joinToString("::") else null
        childrenOf.getOrPut(parentPath) { mutableListOf() }.add(path)
    }

    fun build(parentPath: String?, depth: Int): List<DeckTreeNode> {
        return childrenOf[parentPath].orEmpty()
            .sorted()
            .map { fullName ->
                val deck = byFullName[fullName]
                val leaf = fullName.split("::").last()
                val children = build(fullName, depth + 1)
                DeckTreeNode(
                    deck = deck,
                    fullName = fullName,
                    leafName = leaf,
                    depth = depth,
                    children = children,
                )
            }
    }

    return build(null, 0)
}

/**
 * Flatten the tree into an ordered list for a LazyColumn, respecting which
 * nodes are currently expanded.
 */
fun flattenDeckTree(
    roots: List<DeckTreeNode>,
    expandedIds: Set<Long>,
): List<FlatDeckItem> = buildList {
    fun visit(node: DeckTreeNode) {
        val expanded = node.id in expandedIds
        add(
            FlatDeckItem(
                node = node,
                depth = node.depth,
                isExpanded = expanded,
                hasChildren = node.hasChildren,
            )
        )
        if (expanded) {
            node.children.forEach { visit(it) }
        }
    }
    roots.forEach { visit(it) }
}

/**
 * Collect all node IDs in the tree (used to default everything to expanded).
 */
fun allNodeIds(roots: List<DeckTreeNode>): Set<Long> = buildSet {
    fun visit(node: DeckTreeNode) {
        add(node.id)
        node.children.forEach { visit(it) }
    }
    roots.forEach { visit(it) }
}

/**
 * Return the set of node IDs whose subtree contains a deck matching [query]
 * (case-insensitive substring match on fullName or leafName).  Used to
 * auto-expand matching branches during search.
 */
fun searchMatchingIds(
    roots: List<DeckTreeNode>,
    query: String,
): Pair<Set<Long>, Set<Long>> {
    val matches = mutableSetOf<Long>()      // nodes that directly match
    val expanded = mutableSetOf<Long>()      // ancestors to expand

    fun visit(node: DeckTreeNode): Boolean {
        val selfMatch = query.isBlank() ||
            node.leafName.contains(query, ignoreCase = true) ||
            node.fullName.contains(query, ignoreCase = true)

        var childMatch = false
        for (child in node.children) {
            if (visit(child)) childMatch = true
        }

        if (selfMatch) matches += node.id
        if (childMatch) expanded += node.id   // expand parent so match is visible

        return selfMatch || childMatch
    }
    roots.forEach { visit(it) }
    return matches to expanded
}
