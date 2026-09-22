package xyz.a202132.app.data.model

internal fun applyVisibleNodeOrder(
    existingNodes: List<Node>,
    orderedVisibleNodeIds: List<String>
): List<Node> {
    val current = existingNodes.sortedWith(compareBy<Node> { it.sortOrder }.thenBy { it.id })
    val existingIds = current.mapTo(hashSetOf()) { it.id }
    val requestedIds = orderedVisibleNodeIds.filter { it in existingIds }.distinct()
    if (requestedIds.size < 2) return current.mapIndexed { index, node -> node.copy(sortOrder = index) }

    val requestedSet = requestedIds.toHashSet()
    val nodesById = current.associateBy { it.id }
    val requestedIterator = requestedIds.mapNotNull(nodesById::get).iterator()
    return current
        .map { node ->
            if (node.id in requestedSet && requestedIterator.hasNext()) requestedIterator.next() else node
        }
        .mapIndexed { index, node -> node.copy(sortOrder = index) }
}

internal fun sortNodesByLatencyForDisplay(nodes: List<Node>): List<Node> =
    nodes.sortedWith(
        compareBy<Node> { node ->
            if (node.isAvailable && node.latency >= 0) 0 else 1
        }
            .thenBy { node ->
                if (node.isAvailable && node.latency >= 0) node.latency else Int.MAX_VALUE
            }
            .thenBy { it.sortOrder }
            .thenBy { it.id }
    )
