package app.polyphony.core.playback.queue

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** contract/cases/queue.json: the cases PolyphonyKit's PlayQueue must pass too. */
class UserQueueContractTest {
    /** The player's flat list with flags, driven by UserQueue as the controller drives it. */
    private class Sim(start: List<String>) {
        val items = start.map { it to false }.toMutableList()
        var current = 0
        fun add(id: String, next: Boolean) {
            items.add(UserQueue.insertIndex(items.map { it.second }, current, next), id to true)
        }
        fun advance() { current++ }
        fun play(list: List<String>, at: Int) {
            val waiting = items.drop(current + 1).take(UserQueue.waiting(items.map { it.second }, current)).map { it.first }
            items.clear()
            items += UserQueue.withWaiting(list, at, waiting)
            current = at
        }
    }

    @Test fun `the queue, as the contract has it`() {
        val cases = Json.parseToJsonElement(File("../../contract/cases/queue.json").readText()).jsonObject.getValue("cases").jsonArray
        for (c in cases.map { it.jsonObject }) {
            val sim = Sim(c.getValue("start").jsonArray.map { it.jsonPrimitive.content })
            for (op in c.getValue("ops").jsonArray.map { it.jsonObject }) {
                when {
                    "add" in op -> sim.add(op.getValue("add").jsonPrimitive.content, next = false)
                    "next" in op -> sim.add(op.getValue("next").jsonPrimitive.content, next = true)
                    "advance" in op -> repeat(op.getValue("advance").jsonPrimitive.int) { sim.advance() }
                    "play" in op -> sim.play(op.getValue("play").jsonArray.map { it.jsonPrimitive.content }, op.getValue("at").jsonPrimitive.int)
                }
            }
            val name = c.getValue("name").jsonPrimitive.content
            (c["current"])?.let { assertEquals(name, it.jsonPrimitive.content, sim.items[sim.current].first) }
            val expected = c.getValue("upNext").jsonArray.map { pair -> pair.jsonArray.let { it[0].jsonPrimitive.content to it[1].jsonPrimitive.boolean } }
            assertEquals(name, expected, sim.items.drop(sim.current + 1))
        }
    }
}
