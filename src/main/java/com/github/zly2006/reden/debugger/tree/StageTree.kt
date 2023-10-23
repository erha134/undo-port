package com.github.zly2006.reden.debugger.tree

import com.github.zly2006.reden.debugger.TickStage
import com.github.zly2006.reden.debugger.disableWatchDog
import com.github.zly2006.reden.utils.server
import org.jetbrains.annotations.TestOnly

/**
 * TODO
 */
class StageTree: Iterator<TickStage> {
    class TreeNode(
        val parent: TreeNode?,
        val stage: TickStage,
        var childrenUpdated: Boolean,
        var iter: ListIterator<TickStage>?,
    ) {
        override fun toString() = "$stage ${if (!childrenUpdated) "<>" else "${iter?.previousIndex()} / ${stage.children.size}"}"
    }
    var root: TreeNode? = null
    var child: TreeNode? = null
    val tickedStages = mutableListOf<TickStage>()
    private var lastReturned: TreeNode? = null
    override fun hasNext(): Boolean {
        if (child == null)
            return false
        if (!child!!.childrenUpdated)
            return true
        checkIterators()
        return child != null
    }

    /**
     * Assume that the child node has been ticked and non-null.
     */
    private fun checkIterators() {
        if (child!!.iter == null) {
            child!!.iter = child!!.stage.children.listIterator()
        }

        while (child?.iter?.hasNext() == false) {
            child = child!!.parent
        }
    }

    override fun next(): TickStage {
        if (child == null) {
            error("No child")
        }

        // if we have not ticked the child node, tick it
        if (!child!!.childrenUpdated) {
            child!!.childrenUpdated = true
        } else {
            checkIterators()
            val next = child!!.iter!!.next()
            child = TreeNode(
                child,
                next,
                childrenUpdated = true, // we returned this stage, it should be ticked.
                null
            )
        }

        tickedStages.add(child!!.stage)
        lastReturned = child
        return lastReturned!!.stage
    }

    fun clear() {
        tickedStages.clear()
        root = null
        lastReturned = null
        child = null
    }

    fun resetTo(stage: TickStage) {
        TODO("still need to reset all iterators to current tick stage")
    }

    fun pauseGame() {
        server.timeReference = Long.MAX_VALUE
        disableWatchDog = true
    }

    fun peekLeaf(): TickStage {
        return lastReturned?.stage
            ?: error("No last returned")
    }

    fun initRoot(serverRootStage: TickStage, childrenUpdated: Boolean) {
        clear()
        root = TreeNode(null, serverRootStage, childrenUpdated, null)
        child = root
    }

    @TestOnly
    fun printTree() {
        val list = mutableListOf<TreeNode>()
        var node = child
        while (node != null) {
            list.add(node)
            node = node.parent
        }
        list.reverse()
        list.forEach {
            println(it)
        }
    }

    fun insert2child(stage: TickStage) {
        print("before insert: ")
        printTree()

        val child = lastReturned ?: error("No child, check peekLeaf().")
        if (child.iter == null) {
            child.iter = child.stage.children.listIterator()
        }
        val childIter = child.iter as? MutableListIterator<TickStage>
            ?: error("Child iter is not mutable")
        val newChild = TreeNode(
            child,
            stage,
            false,
            null
        )
        childIter.add(stage)
        childIter.previous() // move back to the inserted stage
        this.child = newChild

        print("after insert: ")
        printTree()
    }
}
