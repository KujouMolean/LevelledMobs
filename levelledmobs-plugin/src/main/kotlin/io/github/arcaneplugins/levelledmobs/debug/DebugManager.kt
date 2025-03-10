package io.github.arcaneplugins.levelledmobs.debug

import io.github.arcaneplugins.levelledmobs.LevelledMobs
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.function.Supplier
import io.github.arcaneplugins.levelledmobs.LivingEntityInterface
import io.github.arcaneplugins.levelledmobs.rules.RuleInfo
import io.github.arcaneplugins.levelledmobs.util.Log
import io.github.arcaneplugins.levelledmobs.util.MessageUtils.colorizeAll
import io.github.arcaneplugins.levelledmobs.wrappers.LivingEntityWrapper
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.util.UUID
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import java.util.concurrent.TimeUnit
import kotlin.math.floor

/**
 * Provides the logic for the debug system
 *
 * @author stumper66
 * @since 3.14.0
 */
class DebugManager {
    private val defaultPlayerDistance = 16
    var isEnabled = false
        private set
    var isTimerEnabled = false
        private set
    var bypassAllFilters = false
        private set
    private var timerEndTime: Instant? = null
    private var timerTask: ScheduledTask? = null
    val filterDebugTypes = mutableSetOf<DebugType>()
    val filterEntityTypes = mutableSetOf<EntityType>()
    val filterRuleNames = mutableSetOf<String>()
    val filterPlayerNames = mutableSetOf<String>()
    var playerThatEnabledDebug: Player? = null
    var listenFor = ListenFor.BOTH
    var outputType = OutputTypes.TO_CONSOLE
    var maxPlayerDistance: Int? = null
    var minYLevel: Int? = null
    var maxYLevel: Int? = null
    var disableAfter: Long? = null
    var disableAfterStr: String? = null
    var damageDebugOutputIsEnabled = false
        private set

    init {
        instance = this
        maxPlayerDistance = defaultPlayerDistance
    }

    fun enableDebug(
        sender: CommandSender,
        usetimer: Boolean,
        bypassFilters: Boolean
    ) {
        if (sender is Player) this.playerThatEnabledDebug = sender
        this.bypassAllFilters = bypassFilters
        this.isEnabled = true
        checkTimerSettings(usetimer)
    }

    fun disableDebug() {
        this.isEnabled = false
        this.isTimerEnabled = false
        toggleDamageDebugOutput(false)
        disableTimer()
    }

    private fun disableTimer() {
        isTimerEnabled = false

        if (this.timerTask == null) {
            return
        }

        timerTask!!.cancel()
        this.timerTask = null
    }

    private fun checkTimerSettings(useTimer: Boolean) {
        if (!isEnabled) return

        val canUseTimer = this.disableAfter != null && disableAfter!! > 0L
        if (!useTimer || !canUseTimer) {
            disableTimer()
            return
        }

        this.timerEndTime = Instant.now().plusMillis(disableAfter!!)

        if (!this.isTimerEnabled) {
            this.isTimerEnabled = true
            Bukkit.getAsyncScheduler().runAtFixedRate(LevelledMobs.instance, {
                this.timerLoop()
            }, 20, 20, TimeUnit.MILLISECONDS)
        }
    }

    companion object{
        private lateinit var instance: DebugManager
        private val longMessagesMap = mutableMapOf<UUID, MutableList<Supplier<String>>>()
        private val lock = Any()

        fun log(
            debugType: DebugType,
            ruleInfo: RuleInfo,
            lmEntity: LivingEntityWrapper?,
            msg: Supplier<String?>
        ) {
            instance.logInstance(debugType, ruleInfo, lmEntity, null, null, msg.get()!!)
        }

        fun log(
            debugType: DebugType,
            ruleInfo: RuleInfo,
            lmInterface: LivingEntityInterface?,
            ruleResult: Boolean,
            msg: Supplier<String?>
        ) {
            instance.logInstance(debugType, ruleInfo, lmInterface, null, ruleResult, msg.get()!!)
        }

        fun log(
            debugType: DebugType,
            lmEntity: LivingEntityWrapper?,
            msg: Supplier<String?>
        ) {
            instance.logInstance(debugType, null, lmEntity, null, null, msg.get()!!)
        }

        fun logNoComma(
            debugType: DebugType,
            lmEntity: LivingEntityWrapper?,
            msg: Supplier<String?>
        ) {
            instance.logInstance(debugType, null, lmEntity, null, null, msg.get()!!, false)
        }

        fun log(
            debugType: DebugType,
            lmEntity: LivingEntityWrapper?,
            result: Boolean,
            msg: Supplier<String?>
        ) {
            instance.logInstance(debugType, null, lmEntity, null, result, msg.get()!!)
        }

        fun log(
            debugType: DebugType,
            entity: Entity?,
            result: Boolean,
            msg: Supplier<String?>
        ) {
            instance.logInstance(debugType, null, null, entity, result, msg.get()!!)
        }

        fun log(
            debugType: DebugType,
            entity: Entity?,
            msg: Supplier<String?>
        ) {
            instance.logInstance(debugType, null, null, entity, null, msg.get()!!)
        }

        /**
         * Sends a debug message to console if enabled in settings
         *
         * @param debugType Reference to whereabouts the debug log is called so that it can be traced
         * back easily
         * @param msg       Message to help de-bugging
         */
        fun log(debugType: DebugType, msg: Supplier<String?>) {
            val message = msg.get() ?: return
            instance.logInstance(debugType, null, null, null, null, message)
        }

        fun startLongDebugMessage(): UUID{
            val id = UUID.randomUUID()
            synchronized (lock){
                longMessagesMap[id] = mutableListOf()
            }

            return id
        }

        fun logLongMessage(id: UUID, message: Supplier<String>){
            if (!instance.isEnabled) return

            synchronized(lock) {
                longMessagesMap[id]?.add(message)
            }
        }

        fun endLongMessage(
            id: UUID,
            debugType: DebugType,
            lmEntity: LivingEntityWrapper?
        ){
            val messages: MutableList<Supplier<String>>?

            synchronized(lock){
                messages = longMessagesMap.remove(id)
            }

            if (messages == null) return
            val sb = StringBuilder()
            for (message in messages)
                sb.append(message.get())

            log(debugType, lmEntity){ sb.toString() }
        }
    }

    private fun logInstance(
        debugType: DebugType,
        ruleInfo: RuleInfo?,
        lmInterface: LivingEntityInterface?,
        entity: Entity?,
        ruleResult: Boolean?,
        origMsg: String,
        useComma: Boolean = true
    ) {
        if (!isEnabled) return
        var msg = origMsg

        // now you have to pass all of the filters if they are configured
        if (!bypassAllFilters) {
            if (filterDebugTypes.isNotEmpty() && !filterDebugTypes.contains(debugType)) return

            if (ruleInfo != null && filterRuleNames.isNotEmpty() &&
                !filterRuleNames.contains(ruleInfo.ruleName.replace(" ", "_")) ||
                ruleInfo == null && filterRuleNames.isNotEmpty()
            ) {
                return
            }

            if (filterEntityTypes.isNotEmpty()) {
                var et: EntityType? = null
                if (entity != null) et = entity.type
                else if (lmInterface != null) et = lmInterface.entityType
                if (!filterEntityTypes.contains(et)) return
            }

            var useEntity = entity
            if (lmInterface is LivingEntityWrapper) useEntity = lmInterface.livingEntity

            if (maxPlayerDistance != null && maxPlayerDistance!! > 0 && useEntity != null) {
                val players = getPlayers()
                var foundMatch = false
                if (players != null) {
                    for (player in players) {
                        if (player.world != useEntity.world) continue
                        val dist = player.location.distance(useEntity.location)
                        if (dist <= maxPlayerDistance!!) {
                            foundMatch = true
                            break
                        }
                    }
                }

                if (!foundMatch) return
            }

            if (ruleResult != null && listenFor != ListenFor.BOTH) {
                if (ruleResult && listenFor == ListenFor.FAILURE) return
                if (!ruleResult && listenFor == ListenFor.SUCCESS) return
            }

            if (useEntity != null) {
                if (minYLevel != null && useEntity.location.blockY < minYLevel!!) return
                if (maxYLevel != null && useEntity.location.blockY > maxYLevel!!) return
            }
        } // end bypass all

        if (ruleInfo != null){
            msg = if (origMsg.isEmpty())
                "(${ruleInfo.ruleName})"
            else
                "(${ruleInfo.ruleName}) $msg"
        }

        if (lmInterface != null){
            val lmEntity = lmInterface as? LivingEntityWrapper
            val useName = lmEntity?.nameIfBaby ?: lmInterface.typeName
            var lvl = lmEntity?.mobLevel
            if (lmInterface.summonedLevel != null) lvl = lmInterface.summonedLevel
            val lvlInfo = if (lvl != null) " (&7lvl $lvl&r)" else " (&7no lvl&r)"
            val addedComma = if (useComma) ", " else ""

            msg = if (msg.isEmpty())
                "mob: &b$useName&7$lvlInfo"
            else
                "mob: &b$useName&7$lvlInfo$addedComma$msg"
        }
        else if (entity != null){
            val addedComma = if (useComma) ", " else ""

            msg = if (msg.isEmpty())
                "mob: &b${entity.type}&7"
            else
                "mob: &b${entity.type}&7$addedComma$msg"
        }

        if (ruleResult != null) {
            if (msg.isEmpty())
                msg = "result: $ruleResult"
            else
                msg += ", result: $ruleResult"
        }

        if (outputType == OutputTypes.TO_BOTH || outputType == OutputTypes.TO_CONSOLE) {
            Log.inf("&8[&bDebug: $debugType&8]&7 $msg")
        }
        if (outputType == OutputTypes.TO_BOTH || outputType == OutputTypes.TO_CHAT) {
            if (playerThatEnabledDebug == null) {
                Log.inf("No player to send chat messages to")
            } else {
                playerThatEnabledDebug!!.sendMessage(
                    colorizeAll(
                        "&8[&bDebug: $debugType&8]&7 $msg"
                    )
                )
            }
        }
    }

    private fun getPlayers(): MutableList<Player>? {
        if (filterPlayerNames.isEmpty()) {
            return Bukkit.getOnlinePlayers().toMutableList()
        }

        val players = mutableListOf<Player>()
        for (playerName in filterPlayerNames) {
            val player = Bukkit.getPlayer(playerName)
            if (player != null) players.add(player)
        }

        return if (players.isEmpty()) null else players
    }

    fun getDebugStatus(): String {
        val sb = StringBuilder("\nDebug Status: ")
        if (isEnabled) {
            sb.append("ENABLED")
            if (isTimerEnabled) {
                sb.append("-(Time Left: ")
                sb.append(getTimeRemaining()).append(")")
            }
        } else sb.append("DISABLED")

        if (!bypassAllFilters && !hasFiltering()) return sb.toString()
        sb.append("\n--------------------------\n")
            .append("Current Filter Options:")

        if (bypassAllFilters) {
            sb.append("\n- All filters bypassed")
            return sb.toString()
        }

        if (filterDebugTypes.isNotEmpty()) {
            sb.append("\n- Debug types: ")
            sb.append(filterDebugTypes)
        }

        if (filterEntityTypes.isNotEmpty()) {
            sb.append("\n- Entity types: ")
            sb.append(filterEntityTypes)
        }

        if (filterRuleNames.isNotEmpty()) {
            sb.append("\n- Rule names: ")
            sb.append(filterRuleNames)
        }

        if (filterPlayerNames.isNotEmpty()) {
            sb.append("\n- Player names: ")
            sb.append(filterPlayerNames)
        }

        if (listenFor != ListenFor.BOTH) {
            sb.append("\n- Listen for: ")
            sb.append(listenFor.name.lowercase(Locale.getDefault()))
        }

        if (maxPlayerDistance != null) {
            sb.append("\n- Max player distance: ")
            sb.append(maxPlayerDistance)
        }

        if (minYLevel != null) {
            sb.append("\n- Min y level: ")
            sb.append(minYLevel)
        }

        if (maxYLevel != null) {
            if (minYLevel != null) sb.append(", Max y level: ")
            else sb.append("\n- Max y level: ")
            sb.append(maxYLevel)
        }

        if (outputType != OutputTypes.TO_CONSOLE) {
            sb.append("\n- Output to: ")
            sb.append(outputType.name.lowercase(Locale.getDefault()))
        }

        return sb.toString()
    }

    private fun hasFiltering(): Boolean {
        return (filterDebugTypes.isNotEmpty() ||
                filterEntityTypes.isNotEmpty() ||
                filterRuleNames.isNotEmpty() ||
                filterPlayerNames.isNotEmpty() || listenFor != ListenFor.BOTH || outputType != OutputTypes.TO_CONSOLE ||
                    maxPlayerDistance == null || maxPlayerDistance != 0 || minYLevel != null || maxYLevel != null
                )
    }

    fun resetFilters() {
        filterDebugTypes.clear()
        filterEntityTypes.clear()
        filterRuleNames.clear()
        filterPlayerNames.clear()
        listenFor = ListenFor.BOTH
        outputType = OutputTypes.TO_CONSOLE
        maxPlayerDistance = defaultPlayerDistance
        minYLevel = null
        maxYLevel = null
        disableAfter = null
        disableAfterStr = null
    }

    enum class ListenFor {
        FAILURE, SUCCESS, BOTH
    }

    enum class OutputTypes {
        TO_CONSOLE, TO_CHAT, TO_BOTH
    }

    fun isDebugTypeEnabled(debugType: DebugType): Boolean {
        if (!this.isEnabled) return false

        return filterDebugTypes.isEmpty() || filterDebugTypes.contains(debugType)
    }

    private fun timerLoop() {
        if (Instant.now().isAfter(this.timerEndTime)) {
            disableDebug()

            val msg = "Debug timer has elapsed, debugging is now disabled"
            if (outputType == OutputTypes.TO_CONSOLE || outputType == OutputTypes.TO_BOTH) {
                Log.inf(msg)
            }
            if ((outputType == OutputTypes.TO_CHAT || outputType == OutputTypes.TO_BOTH)
                && playerThatEnabledDebug != null
            ) {
                playerThatEnabledDebug!!.sendMessage(msg)
            }
        }
    }

    fun timerWasChanged(
        useTimer: Boolean
    ) {
        checkTimerSettings(isTimerEnabled || useTimer)
    }

    private fun getTimeRemaining(): String? {
        if (!isEnabled || disableAfter == null || disableAfter!! <= 0 || timerEndTime == null) return null

        val duration = Duration.between(Instant.now(), timerEndTime)
        val secondsLeft = duration.seconds.toInt()
        if (secondsLeft < 60) {
            return if (secondsLeft == 1) "1 second" else "$secondsLeft seconds"
        } else if (secondsLeft < 3600) {
            val minutes = floor(secondsLeft.toDouble() / 60.0).toInt()
            val newSeconds = secondsLeft % 60
            val sb = StringBuilder()
            sb.append(minutes)
                .append(if (minutes == 1) " minute, " else " minutes, ")
                .append(newSeconds)
                .append(if (newSeconds == 1) " second" else " seconds")
            return sb.toString()
        }

        return secondsLeft.toString()
    }

    fun toggleDamageDebugOutput(doEnable: Boolean){
        if (doEnable) {
            if (damageDebugOutputIsEnabled) return
            // we'll load and unload this listener based on the above setting when reloading
            damageDebugOutputIsEnabled = true
            Bukkit.getPluginManager().registerEvents(LevelledMobs.instance.entityDamageDebugListener, LevelledMobs.instance)
        }
        else{
            if (!damageDebugOutputIsEnabled) return
            damageDebugOutputIsEnabled = false
            HandlerList.unregisterAll(LevelledMobs.instance.entityDamageDebugListener)
        }
    }
}