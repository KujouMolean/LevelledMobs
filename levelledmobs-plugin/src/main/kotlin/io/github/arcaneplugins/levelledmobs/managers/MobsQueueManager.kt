package io.github.arcaneplugins.levelledmobs.managers

import com.molean.folia.adapter.Folia
import java.util.concurrent.LinkedBlockingQueue
import io.github.arcaneplugins.levelledmobs.LevelledMobs
import io.github.arcaneplugins.levelledmobs.debug.DebugManager
import io.github.arcaneplugins.levelledmobs.debug.DebugType
import io.github.arcaneplugins.levelledmobs.misc.EvaluationException
import io.github.arcaneplugins.levelledmobs.misc.QueueItem
import io.github.arcaneplugins.levelledmobs.util.Log
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Queues up mob info so they can be processed in a background thread
 *
 * @author stumper66
 * @since 3.0.0
 */
class MobsQueueManager {
    private var isRunning = false
    private var doThread = false
    private val queue = LinkedBlockingQueue<QueueItem>()
    private val processingList = mutableListOf<UUID>()
    private val maxThreads = 3
    var ignoreMobsWithNoPlayerContext = false
    var queueTasks = CopyOnWriteArraySet< ScheduledTask>()
    private val threadsCount = AtomicInteger()
    private val queueLock = Any()

    fun start() {
        if (isRunning) {
            return
        }
        doThread = true
        isRunning = true
        queueTasks.clear()

        for (i in 0..<maxThreads)
            startAThread()
    }

    private fun startAThread(){
        val bgThread = Runnable {
            try {
                mainThread()
            } catch (ignored: InterruptedException) {
                isRunning = false
            }
            doneWithThread()
        }

        threadsCount.getAndIncrement()
        val task = Folia.getScheduler().runTaskAsynchronously(LevelledMobs.instance, bgThread)
        queueTasks.add(task)
    }

    fun getNumberQueued(): Int{
        val size: Int
        synchronized(queueLock){
            size = queue.size
        }

        return size
    }

    fun clearQueue(){
        synchronized(queueLock){
            queue.clear()
        }
    }

    private fun doneWithThread(){
        threadsCount.getAndDecrement()
        if (threadsCount.get() == 0) {
            isRunning = false
            Log.inf("Mob processing queue Manager has exited")
        }
    }

    fun stop() {
        doThread = false
    }

    fun taskChecker(){
        val queueSize = getNumberQueued()
        val stopAll = queueSize >= 1000
        var threadsNeeded = 0
        for (taskEntry in ArrayList(queueTasks)) {
            if (!stopAll && (!taskEntry.isCancelled || taskEntry.executionState == ScheduledTask.ExecutionState.RUNNING)) continue
            val status = if (taskEntry.isCancelled) "cancelled"
            else if (!stopAll) "not running"
            else "queue size was $queueSize"

            Log.war("Restarting Nametag Queue Manager task, status was $status")
            taskEntry.cancel()
            queueTasks.remove(taskEntry)
            threadsCount.getAndDecrement()
            threadsNeeded++
        }
        if (threadsNeeded == 0) return

        for (i in 0..<threadsNeeded)
            startAThread()
    }

    fun addToQueue(item: QueueItem) {
        item.lmEntity.inUseCount.getAndIncrement()

        var offeredItem = false
        synchronized(queueLock){
            if (!processingList.contains(item.entityId)) {
                queue.offer(item)
                offeredItem = true
            }
        }

        if (!offeredItem)
            item.lmEntity.free()
    }

    private fun mainThread() {
        while (doThread) {
            val item: QueueItem?
            synchronized(queueLock){
                item = queue.poll()
                if (item != null)
                    processingList.add(item.entityId)
            }
            if (item == null) {
                Thread.sleep(2L)
                continue
            }

            try {
                processItem(item)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                synchronized(queueLock){
                    processingList.remove(item.entityId)
                }
                item.lmEntity.free()
            }
        }

        doneWithThread()
    }

    private fun processItem(item: QueueItem) {
        if (!item.lmEntity.isPopulated) return

        if (ignoreMobsWithNoPlayerContext && item.lmEntity.associatedPlayer == null
            && LevelledMobs.instance.rulesManager.isPlayerLevellingEnabled()){
            DebugManager.log(DebugType.PLAYER_CONTEXT, item.lmEntity){
                val locationStr = "${item.lmEntity.location.blockX}, " +
                    "${item.lmEntity.location.blockY}, " +
                    "${item.lmEntity.location.blockZ} " +
                    "in ${item.lmEntity.location.world.name}"
                "ignoring mob ${item.lmEntity.nameIfBaby} due to no player context at $locationStr"
            }
            return
        }

        try{
            LevelledMobs.instance.levelManager.entitySpawnListener.processMob(item.lmEntity, item.event)
        }

        catch (ignored: EvaluationException){
            // this exception is manually thrown after logging and notifying op users
        }
        catch (e: java.util.concurrent.TimeoutException){
            DebugManager.log(DebugType.APPLY_LEVEL_RESULT, item.lmEntity, false){
                "Timed out applying level to mob"
            }
        }
    }
}