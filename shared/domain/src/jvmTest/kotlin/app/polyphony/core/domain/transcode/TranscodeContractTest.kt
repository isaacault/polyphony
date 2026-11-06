package app.polyphony.core.domain.transcode

import app.polyphony.core.domain.model.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** contract/cases/transcode.json: the cases the Swift side (PolyphonyKit) must pass too. */
class TranscodeContractTest {
    private val cases: JsonObject = Json.parseToJsonElement(File("../../contract/cases/transcode.json").readText()).jsonObject

    private fun JsonObject.bool(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    @Test fun `which profile applies, as the contract has it`() {
        for (case in cases.getValue("resolver").jsonArray.map { it.jsonObject }) {
            val p = case["policy"]?.jsonObject ?: JsonObject(emptyMap())
            val base = TranscodePolicy()
            val policy = base.copy(
                defaultProfileId = p.text("default") ?: base.defaultProfileId,
                wifiProfileId = p.text("wifi") ?: base.wifiProfileId,
                cellularProfileId = if (p.containsKey("cellular")) p.text("cellular") else base.cellularProfileId,
                castDefaultProfileId = p.text("castDefault") ?: base.castDefaultProfileId,
                meteredWifiAsCellular = p.bool("meteredAsCellular") ?: base.meteredWifiAsCellular,
                awayWifiAsCellular = p.bool("awayAsCellular") ?: base.awayWifiAsCellular,
                adaptiveLatency = base.adaptiveLatency.copy(
                    applyToRemoteOutputs = p.bool("capRemote") ?: base.adaptiveLatency.applyToRemoteOutputs,
                    enabled = p.bool("capEnabled") ?: base.adaptiveLatency.enabled,
                ),
            )
            val n = case.getValue("network").jsonObject
            val context = TranscodeContext(
                network = NetworkState(NetworkKind.valueOf(n.text("kind")!!), isMetered = n.bool("metered")!!),
                target = if (case.bool("remote") == true) OutputTarget.Dlna("u", "n") else OutputTarget.Local,
                latencyDegraded = case.bool("latencyDegraded") ?: false,
                deviceProfileId = case.text("deviceProfile"),
                atHome = case.bool("atHome"),
            )
            val d = TranscodeResolver().resolve(policy, TranscodeProfile.BUILT_INS, context)
            val about = case.text("about")
            assertEquals(about, case.text("profile"), d.profile.id)
            assertEquals(about, case.text("reason"), d.reason.name)
            assertEquals(about, case.getValue("capped").jsonPrimitive.boolean, d.cappedByLatency)
        }
    }

    @Test fun `an already-compressed file stays as it is, as the contract has it`() {
        for (case in cases.getValue("lossySource").jsonArray.map { it.jsonObject }) {
            val decision = TranscodeDecision(
                profile = TranscodeProfile.builtIn(case.text("profile")!!)!!,
                reason = TranscodeDecision.Reason.DEFAULT,
                cappedByLatency = case.bool("capped") ?: false,
            )
            val track = Track(id = "t", serverId = "s", title = "t", durationMs = 1, container = case.text("container"))
            assertEquals(case.text("about"), case.getValue("keeps").jsonPrimitive.boolean, LossySourceRule.keepsOriginal(decision, track))
        }
    }
}
